package no.heimflyt.launcher.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import no.heimflyt.launcher.theme.create.DesktopVariants
import no.heimflyt.launcher.theme.create.ExportImages
import no.heimflyt.launcher.theme.image.Protection
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.FallbackPalette
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.store.BackgroundChoice
import no.heimflyt.launcher.theme.store.BackgroundKind
import no.heimflyt.launcher.theme.store.BundledSource
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.CreateRecipe
import no.heimflyt.launcher.theme.store.StoredBackground
import no.heimflyt.launcher.theme.store.GenerationPreparer
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.Strength
import no.heimflyt.launcher.theme.store.ThemeChoice
import no.heimflyt.launcher.theme.store.ThemeFiles
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.store.ThemeRecord
import no.heimflyt.launcher.theme.store.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** R1 fault injection (THEME_ARCHITECTURE.md §8.3, spec §10.1): after any failure or kill, restart loads a complete old or new generation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ThemeStoreTest {
    private lateinit var root: File
    private val sig = ZoneSignature(1f, 2.625f, 1080, 2424, 0, 142, 0, 126, 142, 63)

    /** Simulated process death: nothing after it runs, including cleanup. */
    class Killed : Error()

    open class JvmFiles : ThemeFiles {
        override fun write(file: File, bytes: ByteArray) { FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() } }
        override fun rename(from: File, to: File) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        override fun syncDir(dir: File) { runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } } }
        override fun mkdir(dir: File) { if (!dir.isDirectory && !dir.mkdir()) throw IOException("mkdir") }
    }

    /** Fails (IOException) or kills (Error) at mutating operation number [at]; counts every write, sync, rename and mkdir. */
    class Faulty(private val at: Int, private val kill: Boolean) : JvmFiles() {
        var ops = 0; var dead = false
        private fun step(what: String) {
            if (dead) throw Killed()
            if (++ops == at) { if (kill) { dead = true; throw Killed() } else throw IOException("injected at $what") }
        }
        override fun write(file: File, bytes: ByteArray) { step("write ${file.name}"); super.write(file, bytes) }
        override fun rename(from: File, to: File) { step("rename ${to.name}"); super.rename(from, to) }
        override fun syncDir(dir: File) { step("sync ${dir.name}"); super.syncDir(dir) }
        override fun mkdir(dir: File) { step("mkdir ${dir.name}"); super.mkdir(dir) }
        override fun deleteTree(dir: File) { if (dead) throw Killed(); super.deleteTree(dir) }
    }

    private val light = OmarchyPalette(mapOf("background" to 0xfffcf0, "foreground" to 0x100f0f, "accent" to 0x205ea6, "lighter_background" to 0xf2f0e5), true)
    private val bundled = object : BundledSource {
        override val ids = listOf("bundled:krets", "bundled:tokyo-night", "bundled:paper")
        override fun load(id: String) = when (id) {
            "bundled:krets" -> rec(id, "Krets", FallbackPalette.krets)
            "bundled:tokyo-night" -> rec(id, "Tokyo Night", FallbackPalette.tokyoNight)
            "bundled:paper" -> rec(id, "Paper", light)
            else -> null
        }
        private fun rec(id: String, name: String, p: OmarchyPalette) =
            LoadedRecord(ThemeRecord(id, name, ThemeOrigin.Bundled, p.light, PaletteSource.BUNDLED, emptyList(), null, null, emptyList(), null), ThemeStore.BUNDLED_REV, p, null)
    }

    /** Writes a deterministic stand-in "image" through the store's files; optionally blocks until released. */
    inner class FakePreparer(private val files: () -> ThemeFiles) : GenerationPreparer {
        var gate: CountDownLatch? = null; var entered: CountDownLatch? = null
        override fun prepare(record: LoadedRecord, choice: ThemeChoice, tokens: ResolvedColors, signature: ZoneSignature, dir: File, cancelled: () -> Boolean,
                             out: String): Protection {
            val g = gate; entered?.countDown(); g?.await(5, TimeUnit.SECONDS)
            files().write(File(dir, out), "${record.record.id}|${choice.background.encode()}|${choice.framing}|${choice.strength}".toByteArray())
            return Protection(statusLight = tokens.isLight, navLight = tokens.isLight)
        }
        override fun check(file: File, signature: ZoneSignature) = file.isFile
    }

    private fun store(files: ThemeFiles, preparer: GenerationPreparer? = null): ThemeStore {
        val holder = arrayOf(files)
        return ThemeStore(root, files, bundled, preparer ?: FakePreparer { holder[0] })
    }

    @Before fun setUp() { root = Files.createTempDirectory("themes").toFile() }

    private fun restartState(): Pair<ThemeStore.LoadState, String?> {
        val s = store(JvmFiles())
        val state = s.loadActive()
        return state to s.active.value?.gen
    }

    private fun installFile(s: ThemeStore, id: String, name: String, files: ThemeFiles = JvmFiles()): Outcome {
        val request = s.beginRecordRequest(id)
        val (stage, pin) = s.newStaging()
        pin.use {
            files.write(File(stage, "colors.toml"), ColorsToml.write(light).toByteArray())
            val record = ThemeRecord(id, name, ThemeOrigin.FileImport("$name.zip", "abc"), true, PaletteSource.COLORS_TOML, emptyList(), null, null, emptyList(), 1L)
            files.write(File(stage, "manifest.json"), record.toJson().toString().toByteArray())
            return s.commitRecord(stage, id, request)
        }
    }

    @Test fun staleRecordRevisionCannotReplaceANewerTheme() {
        val files = JvmFiles(); val s = store(files); val id = "file:stale"
        assertTrue(installFile(s, id, "Current", files) is Outcome.Installed)
        val current = s.loadRecord(id)!!
        val request = s.beginRecordRequest(id)
        val (stage, pin) = s.newStaging()
        pin.use {
            files.write(File(stage, "colors.toml"), ColorsToml.write(light).toByteArray())
            files.write(File(stage, "manifest.json"), current.record.copy(name = "Stale").toJson().toString().toByteArray())
            assertEquals(Outcome.Superseded, s.commitRecord(stage, id, request, "older-revision"))
        }
        assertEquals("Current", s.loadRecord(id)!!.record.name)
    }

    @Test fun selectedDesktopImageSurvivesRecordCommitAndReplacesOnlyTheExportedBackground() {
        val files = JvmFiles(); val s = store(files); val id = "created:desktop"
        val red = Bitmap.createBitmap(320, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val master = ByteArrayOutputStream().also { red.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        val request = s.beginRecordRequest(id)
        val (stage, pin) = s.newStaging()
        pin.use {
            files.write(File(stage, "colors.toml"), ColorsToml.write(light).toByteArray())
            files.write(File(stage, "master-0.webp"), master)
            files.write(File(stage, "thumb-0.webp"), master)
            val recipe = CreateRecipe(1, "Calm", null, null, "dark", Framing(), Strength.BALANCED, emptyList(), emptyList())
            val rec = ThemeRecord(id, "Desktop", ThemeOrigin.Created(recipe, 1L), true, PaletteSource.GENERATED,
                listOf(StoredBackground("master-0.webp", "thumb-0.webp", "Desktop", 320, 400, ThemeStore.sha256(master), false)),
                null, null, emptyList(), 1L)
            files.write(File(stage, "manifest.json"), rec.toJson().toString().toByteArray())
            assertTrue(s.commitRecord(stage, id, request) is Outcome.Installed)
        }
        val original = s.loadRecord(id)!!
        assertTrue(DesktopVariants(s, files).attach(original, 0, blue) is Outcome.Installed)
        val loaded = s.loadRecord(id)!!
        assertEquals(1, loaded.record.desktopVariants.size)
        assertEquals(loaded.record.desktopVariants, ThemeRecord.fromJson(loaded.record.toJson()).desktopVariants)
        val zip = ByteArrayOutputStream()
        ExportImages.write(zip, loaded.record.name, loaded.palette, listOf(File(loaded.dir, "master-0.webp")), listOf(Framing()),
            mapOf(0 to File(loaded.dir, loaded.record.desktopVariants.single().file)))
        val background = ZipInputStream(zip.toByteArray().inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: error("missing background")
                if (entry.name.contains("/backgrounds/")) break
            }
            z.readBytes()
        }
        val decoded = BitmapFactory.decodeByteArray(background, 0, background.size)
        assertEquals(320, decoded.width); assertEquals(180, decoded.height)
        assertTrue(Color.blue(decoded.getPixel(160, 90)) > 200)
        assertTrue(Color.red(decoded.getPixel(160, 90)) < 60)
        assertTrue(DesktopVariants(s, files).remove(loaded, 0) is Outcome.Installed)
        val restored = s.loadRecord(id)!!
        assertTrue(restored.record.desktopVariants.isEmpty())
        assertFalse(File(restored.dir, "desktop-0.jpg").exists())
        decoded.recycle(); red.recycle(); blue.recycle()
    }

    @Test fun applyPublishesOnlyAfterCommitAndRestartReadsTheSameGeneration() {
        val s = store(JvmFiles())
        assertEquals(ThemeStore.LoadState.NONE, s.loadActive())
        val o = s.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig)
        assertTrue(o is Outcome.Applied)
        val gen = (o as Outcome.Applied).active.gen
        assertEquals(gen, s.active.value!!.gen)
        assertEquals(ThemeStore.LoadState.LOADED to gen, restartState())
        val startup = s.readActivePointer()!!.startup
        assertEquals(0xfffcf0, startup.ground); assertTrue(startup.light)
        // Plain has no image and needs no signature.
        assertTrue(s.apply("bundled:tokyo-night", BackgroundChoice(BackgroundKind.PLAIN), Framing(), Strength.BALANCED, null) is Outcome.Applied)
        assertEquals(null, s.active.value!!.background)
    }

    /** Same-id reframe under a failure or a kill after every single mutating operation. */
    @Test fun faultAfterEveryOperationLeavesACompleteOldOrNewGeneration() {
        for (kill in listOf(false, true)) {
            var at = 1
            while (true) {
                root.deleteRecursively(); root.mkdirs()
                val s0 = store(JvmFiles())
                val old = (s0.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) as Outcome.Applied).active.gen
                val faulty = Faulty(at, kill)
                val s = store(faulty)
                s.loadActive()
                val outcome = try { s.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(0.3f), Strength.SOFT, sig) } catch (_: Killed) { null }
                val (state, gen) = restartState()
                assertEquals("kill=$kill at=$at", ThemeStore.LoadState.LOADED, state)
                when (outcome) {
                    is Outcome.Applied -> assertEquals(outcome.active.gen, gen)
                    is Outcome.Failed -> assertEquals("kill=$kill at=$at failed → old", old, gen)
                    else -> assertTrue("kill=$kill at=$at: complete old or new", gen != null)
                }
                if (faulty.ops < at) break   // the whole transaction ran without reaching the fault
                at++
            }
            assertTrue("exercised ${at} operations", at > 8)
        }
    }

    @Test fun postRenameSyncFailureIsReportedUncertainAndKeepsBothGenerations() {
        val s0 = store(JvmFiles())
        val old = (s0.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) as Outcome.Applied).active.gen
        // Fail exactly the themes/ directory sync that follows the pointer rename.
        val files = object : JvmFiles() { var renamedPointer = false
            override fun rename(from: File, to: File) { super.rename(from, to); if (to.name == "active.json") renamedPointer = true }
            override fun syncDir(dir: File) { if (renamedPointer && dir == root) throw IOException("sync"); super.syncDir(dir) } }
        val s = store(files); s.loadActive()
        val o = s.apply("bundled:tokyo-night", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig)
        assertTrue(o is Outcome.Uncertain)
        assertTrue(File(root, "generations/$old").isDirectory)
        val (_, gen) = restartState()
        assertEquals(s.active.value!!.gen, gen)
    }

    @Test fun competingAppliesLatestRequestWins() {
        val files = JvmFiles()
        val preparer = FakePreparer { files }
        val s = store(files, preparer)
        val gate = CountDownLatch(1)
        preparer.gate = gate; preparer.entered = CountDownLatch(1)
        var older: Outcome? = null
        val t = thread { older = s.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) }
        assertTrue(preparer.entered!!.await(5, TimeUnit.SECONDS))
        preparer.gate = null; preparer.entered = null
        val newer = s.apply("bundled:tokyo-night", BackgroundChoice(BackgroundKind.CONTOUR), Framing(), Strength.BALANCED, sig)
        gate.countDown(); t.join(5000)   // the older job resumes: it must not commit over the newer one
        assertTrue(newer is Outcome.Applied)
        assertEquals(Outcome.Superseded, older)
        assertEquals("bundled:tokyo-night", s.active.value!!.choice.recordId)
        assertEquals("bundled:tokyo-night", restartStateRecord())
    }

    @Test fun restartInitialisesTheSequenceAboveThePersistedOne() {
        val s = store(JvmFiles())
        repeat(3) { s.apply("bundled:paper", BackgroundChoice(BackgroundKind.PLAIN), Framing(), Strength.BALANCED, null) }
        val seq = s.readActivePointer()!!.seq
        val again = store(JvmFiles())
        again.apply("bundled:tokyo-night", BackgroundChoice(BackgroundKind.PLAIN), Framing(), Strength.BALANCED, null)
        assertTrue(again.readActivePointer()!!.seq > seq)
    }

    @Test fun installReinstallAndConcurrentRecordsKeepEveryUnrelatedRecord() {
        val s = store(JvmFiles())
        assertTrue(installFile(s, "file:aaa", "Alpha") is Outcome.Installed)
        assertTrue(installFile(s, "file:bbb", "Beta") is Outcome.Installed)
        val first = s.readCatalog().records["file:aaa"]
        assertTrue(s.apply("file:aaa", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) is Outcome.Applied)
        assertTrue(installFile(s, "file:aaa", "Alpha") is Outcome.Installed)   // reinstall → new revision
        val cat = s.readCatalog()
        assertEquals(setOf("file:aaa", "file:bbb"), cat.records.keys)
        assertNotEquals(first, cat.records["file:aaa"])
        assertFalse("old revision collected", File(root, "records/$first").exists())
        // Home still loads its self-contained generation built from the old revision.
        assertEquals(ThemeStore.LoadState.LOADED, restartState().first)
    }

    @Test fun removeActiveSwitchesFirstAndAFailedSwitchRemovesNothing() {
        val s = store(JvmFiles())
        installFile(s, "file:aaa", "Alpha")
        s.apply("file:aaa", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig)
        // Fallback preparation fails: the active theme and its record stay.
        val failing = object : JvmFiles() { override fun rename(from: File, to: File) { if (to.parentFile?.name == "generations") throw IOException("full"); super.rename(from, to) } }
        val s2 = store(failing); s2.loadActive()
        val o = s2.remove("file:aaa", "Alpha", sig)
        assertEquals(Outcome.Failed("Couldn't switch themes, so Alpha was not removed."), o)
        assertTrue("file:aaa" in s2.readCatalog().records)
        assertEquals("file:aaa", restartStateRecord())
        // A working switch: fallback committed, then the record removed.
        val s3 = store(JvmFiles()); s3.loadActive()
        assertEquals(Outcome.Removed, s3.remove("file:aaa", "Alpha", sig))
        assertFalse("file:aaa" in s3.readCatalog().records)
        assertEquals(ThemeStore.FALLBACK_ID, restartStateRecord())
        assertTrue(s3.remove("bundled:paper", "Paper", sig) is Outcome.Failed)
    }

    @Test fun applyAfterRemoveFailsRecoverably() {
        val files = JvmFiles()
        val preparer = FakePreparer { files }
        val s = store(files, preparer)
        installFile(s, "file:aaa", "Alpha")
        preparer.gate = CountDownLatch(1); preparer.entered = CountDownLatch(1)
        var outcome: Outcome? = null
        val t = thread { outcome = s.apply("file:aaa", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) }
        assertTrue(preparer.entered!!.await(5, TimeUnit.SECONDS))
        val gate = preparer.gate!!
        preparer.gate = null; preparer.entered = null
        assertEquals(Outcome.Removed, s.remove("file:aaa", "Alpha", sig))
        gate.countDown(); t.join(5000)
        assertEquals(Outcome.Failed("That theme was removed."), outcome)
        assertEquals(null, s.active.value)
    }

    /** Multi-image themes (canonical decision 2026-09-29): one prepared Home image per slot, all in one generation. */
    @Test fun multiImageGenerationIsCompleteAndRoundTrips() {
        val s = store(JvmFiles())
        val files = JvmFiles()
        val request = s.beginRecordRequest("created:multi")
        val (stage, pin) = s.newStaging()
        pin.use {
            files.write(File(stage, "colors.toml"), ColorsToml.write(light).toByteArray())
            val bgs = (0..2).map { i ->
                val bytes = "image $i".toByteArray()
                files.write(File(stage, "master-$i.webp"), bytes); files.write(File(stage, "thumb-$i.webp"), bytes)
                no.heimflyt.launcher.theme.store.StoredBackground("master-$i.webp", "thumb-$i.webp", "Image $i", 100, 100, ThemeStore.sha256(bytes), false)
            }
            val record = ThemeRecord("created:multi", "Multi", ThemeOrigin.FileImport("x", "y"), true, PaletteSource.GENERATED, bgs, null, null, emptyList(), 1L)
            files.write(File(stage, "manifest.json"), record.toJson().toString().toByteArray())
            assertTrue(s.commitRecord(stage, "created:multi", request) is Outcome.Installed)
        }
        val extras = listOf(no.heimflyt.launcher.theme.store.ImageSlot(1, Framing(0.2f)), no.heimflyt.launcher.theme.store.ImageSlot(2, Framing(0.8f, 0.5f, 2f)),
            no.heimflyt.launcher.theme.store.ImageSlot(9, Framing()))   // an invalid index is dropped
        val o = s.apply("created:multi", BackgroundChoice(BackgroundKind.IMAGE, 0), Framing(), Strength.BALANCED, sig, extras = extras,
            mode = no.heimflyt.launcher.theme.store.BackgroundMode.ROTATE)
        assertTrue(o is Outcome.Applied)
        val a = (o as Outcome.Applied).active
        assertEquals(listOf("home_bg.webp", "home_bg-1.webp", "home_bg-2.webp"), a.backgrounds.map { it.name })
        assertTrue(a.backgrounds.all { it.isFile })
        assertEquals(3, a.protections.size)
        val again = store(JvmFiles()).also { assertEquals(ThemeStore.LoadState.LOADED, it.loadActive()) }.active.value!!
        assertEquals(a.choice, again.choice)
        assertEquals(no.heimflyt.launcher.theme.store.BackgroundMode.ROTATE, again.choice.mode)
        assertEquals(listOf(0, 1, 2), again.choice.slots.map { it.index })
        assertEquals(Framing(0.8f, 0.5f, 2f), again.choice.slots[2].framing)
        assertEquals(3, again.backgrounds.size)
        // A single image with a mode is stored as Fixed.
        val single = s.apply("created:multi", BackgroundChoice(BackgroundKind.IMAGE, 1), Framing(), Strength.BALANCED, sig, mode = no.heimflyt.launcher.theme.store.BackgroundMode.RANDOM)
        assertEquals(no.heimflyt.launcher.theme.store.BackgroundMode.FIXED, (single as Outcome.Applied).active.choice.mode)
    }

    @Test fun garbageCollectionNeverDeletesAPinnedGeneration() {
        val s = store(JvmFiles())
        val a = (s.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) as Outcome.Applied).active
        val pin = s.pin(a.background!!.parentFile)
        s.apply("bundled:tokyo-night", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig)
        s.gc()
        assertTrue("a reader's pinned generation survives", a.background!!.isFile)
        pin.close(); s.gc()
        assertFalse(a.background!!.parentFile!!.exists())
        assertEquals(1, File(root, "generations").listFiles()!!.size)
    }

    @Test fun corruptGenerationFallsBackInsteadOfMixing() {
        val s = store(JvmFiles())
        val a = (s.apply("bundled:paper", BackgroundChoice(BackgroundKind.DUSK), Framing(), Strength.BALANCED, sig) as Outcome.Applied).active
        a.background!!.writeBytes("tampered".toByteArray())
        assertEquals(ThemeStore.LoadState.FAILED, store(JvmFiles()).loadActive())
        File(root, "active.json").writeText("{")
        assertEquals(ThemeStore.LoadState.FAILED, store(JvmFiles()).loadActive())
        // An unreadable pointer protects the generations from collection.
        s.gc(); assertTrue(a.background!!.parentFile!!.isDirectory)
    }

    private fun restartStateRecord() = store(JvmFiles()).also { it.loadActive() }.active.value?.choice?.recordId
}
