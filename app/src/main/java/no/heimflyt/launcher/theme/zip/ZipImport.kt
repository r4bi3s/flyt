package no.heimflyt.launcher.theme.zip

import android.content.ContentResolver
import android.net.Uri
import no.heimflyt.launcher.theme.InstallReport
import no.heimflyt.launcher.theme.image.ThemeImages
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.PaletteException
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.store.AndroidThemeFiles
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.StoredBackground
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.store.ThemeRecord
import no.heimflyt.launcher.theme.store.ThemeStore
import java.io.File
import java.io.IOException
import java.io.InputStream

/** What the inspect pass found: shown in the Report before anything is installed (THEME_ARCHITECTURE.md §4.5–4.6). */
class ZipReport(val uri: Uri, val fileName: String, val plan: ZipPlan, override val name: String, override val palette: OmarchyPalette,
                override val source: PaletteSource, override val notes: List<String>) : InstallReport {
    override val id get() = "file:" + plan.sha256.take(16)
    override val origin get() = "$fileName · ${plan.sha256.take(7)}"
    override val backgroundCount get() = plan.backgrounds.size
    override val backgroundBytes get() = plan.backgrounds.sumOf { it.size }
    override val hasPreview get() = plan.preview != null
    override val ignored get() = plan.ignored
    override val totalBytes get() = backgroundBytes + (plan.preview?.size ?: 0)
}

/** SAF `.zip` import: read-only access to the picked URI, two bounded passes, a staged revision and one record commit. No network. */
class ZipImport(private val store: ThemeStore, private val resolver: ContentResolver) {
    private fun opener(uri: Uri): () -> InputStream = { resolver.openInputStream(uri) ?: throw IOException("unreadable") }

    /** Inspect pass. Throws [ZipRejected] with owner-facing copy, or [ZipCancelled]. */
    fun inspect(uri: Uri, fileName: String, cancelled: () -> Boolean): ZipReport {
        val plan = try { ZipInventory.inspect(opener(uri), fileName, cancelled = cancelled) }
                   catch (_: IOException) { throw ZipRejected("Couldn't read this file.") }
        val (result, source) = try { OmarchyResolver.fromFiles(plan.colorsToml, plan.alacrittyToml, plan.lightMode) }
                               catch (e: PaletteException) { throw ZipRejected(e.message ?: "The theme's colours couldn't be read.") }
        val notes = plan.notes + result.notes.take(4)
        return ZipReport(uri, fileName, plan, ZipInventory.themeName(plan.rootDir, fileName), result.palette, source, notes)
    }

    /** Install pass into staging, then the record commit. Nothing is visible unless the commit succeeds. */
    fun install(report: ZipReport, displayW: Int, displayH: Int, cancelled: () -> Boolean, progress: (Long) -> Unit): Outcome {
        val files = AndroidThemeFiles
        val request = store.beginRecordRequest(report.id)
        val (stage, pin) = try { store.newStaging() } catch (_: IOException) { return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        try {
            val stored = mutableListOf<StoredBackground>()
            val notes = report.notes.toMutableList()
            var preview: String? = null
            var done = 0L
            ZipInventory.install(opener(report.uri), report.plan, cancelled = cancelled) { file, bytes ->
                if (file === report.plan.preview || file.path == report.plan.preview?.path) preview = ThemeImages.preview(bytes, stage, files)
                else {
                    val r = ThemeImages.background(bytes, file.name, stored.size, displayW, displayH, stage, files)
                    r.background?.let(stored::add); r.note?.let(notes::add)
                }
                done += file.size; progress(done)
            }
            files.write(File(stage, "colors.toml"), ColorsToml.write(report.palette).toByteArray())
            val record = ThemeRecord(report.id, report.name, ThemeOrigin.FileImport(report.fileName.take(120), report.plan.sha256), report.palette.light,
                report.source, stored.sortedBy { it.label }, preview, report.plan.licenseFile, notes.distinct().take(24), System.currentTimeMillis())
            files.write(File(stage, "manifest.json"), record.toJson().toString().toByteArray())
            if (cancelled()) return Outcome.Superseded
            return store.commitRecord(stage, report.id, request)
        } catch (e: ZipRejected) { return Outcome.Failed(e.message ?: "This archive is damaged.") }
        catch (_: ZipCancelled) { return Outcome.Superseded }
        catch (_: IOException) { return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        finally { pin.close(); if (stage.exists()) runCatching { files.deleteTree(stage) } }
    }
}
