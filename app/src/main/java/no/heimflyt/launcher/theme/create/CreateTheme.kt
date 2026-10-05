package no.heimflyt.launcher.theme.create

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import no.heimflyt.launcher.theme.image.Images
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.store.CreateRecipe
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.StoredBackground
import no.heimflyt.launcher.theme.store.DesktopVariant
import no.heimflyt.launcher.theme.store.ThemeFiles
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.store.ThemeRecord
import no.heimflyt.launcher.theme.store.ThemeStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PhotoException(message: String) : Exception(message)

/**
 * One image of a theme being created: the original is re-encoded once to [masterFile] (pixels only, ≤ 4096 px) and only a
 * ≤ 1600 px preview and the ≤ 256 px analysis copy stay in memory, so up to four images fit comfortably.
 */
class Photo(val preview: Bitmap, val analysis: PaletteGenerator.Image, val masterFile: File, val masterW: Int, val masterH: Int)

/**
 * CREATE_THEME.md §6.1: bounded local decoding of a Photo Picker (or stored master) image. The temporary read grant is used
 * once; no persistable permission, no URI kept. Only pixels survive: EXIF/GPS/XMP never reach storage.
 */
object PhotoPrep {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_SIDE = 20_000
    const val MAX_PIXELS = 210_000_000L

    enum class Format { JPEG, PNG, WEBP, GIF, HEIF, AVIF }

    fun sniff(b: ByteArray): Format? {
        fun ascii(o: Int, n: Int) = if (b.size >= o + n) String(b, o, n, Charsets.US_ASCII) else ""
        return when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> Format.JPEG
            b.size >= 4 && b[0] == 0x89.toByte() && ascii(1, 3) == "PNG" -> Format.PNG
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> Format.WEBP
            ascii(0, 4) == "GIF8" -> Format.GIF
            ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("heic", "heix", "hevc", "mif1", "msf1") -> Format.HEIF
            ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("avif", "avis") && Build.VERSION.SDK_INT >= 31 -> Format.AVIF
            else -> null
        }
    }

    /** Reads at most [MAX_BYTES] actually read bytes, even when the provider reports no length. */
    fun readBounded(input: InputStream): ByteArray = input.use { s ->
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L
        while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) throw PhotoException("This image is too large."); out.write(buf, 0, n) }
        out.toByteArray()
    }

    fun masterLimit(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        return if (am == null || am.isLowRamDevice || am.memoryClass < 256) 3072 else 4096
    }

    const val PREVIEW_SIDE = 1600

    /** Picker URI → an upright master re-encoded into [scratch] (app-private cache), plus the in-memory preview/analysis. */
    fun load(resolver: ContentResolver, uri: Uri, maxSide: Int, scratch: File, files: no.heimflyt.launcher.theme.store.ThemeFiles): Photo {
        val bytes = readBounded(resolver.openInputStream(uri) ?: throw PhotoException("Couldn't read this image."))
        if (sniff(bytes) == null) throw PhotoException("This image format isn't supported.")
        val master = if (Build.VERSION.SDK_INT >= 28) decodeP(bytes, maxSide) else decodeLegacy(bytes, maxSide)
        scratch.mkdirs()
        val file = File(scratch, "${UUID.randomUUID()}.webp")
        files.write(file, Images.webp(master, 92))   // pixel re-encoding is the privacy boundary
        val photo = fromMaster(master, file)
        master.recycle()
        return photo
    }

    private fun fromMaster(master: Bitmap, file: File): Photo {
        val s = min(1.0, PREVIEW_SIDE.toDouble() / max(master.width, master.height))
        val preview = if (s < 1) Bitmap.createScaledBitmap(master, (master.width * s).roundToInt(), (master.height * s).roundToInt(), true)
                      else master.copy(Bitmap.Config.ARGB_8888, false)
        return Photo(preview, analysisCopy(preview), file, master.width, master.height)
    }

    /** A stored master (Edit): the preview is decoded sampled; the file itself is copied on save. */
    fun fromFile(file: File): Photo {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0) throw PhotoException("Couldn't read this image.")
        var sample = 1; while (max(o.outWidth, o.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw PhotoException("Couldn't read this image.")
        val s = min(1.0, PREVIEW_SIDE.toDouble() / max(bmp.width, bmp.height))
        val preview = if (s < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true).also { bmp.recycle() } else bmp
        return Photo(preview, analysisCopy(preview), file, o.outWidth, o.outHeight)
    }

    private fun checkSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) throw PhotoException("Couldn't read this image.")
        if (w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) throw PhotoException("This image is too large.")
    }

    /** API 28+: header check in the callback, target-size decode, software allocation, sRGB; ImageDecoder applies EXIF orientation. */
    @androidx.annotation.RequiresApi(28)
    private fun decodeP(bytes: ByteArray, maxSide: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
            checkSize(info.size.width, info.size.height)
            val s = min(1.0, maxSide.toDouble() / max(info.size.width, info.size.height))
            d.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            d.isUnpremultipliedRequired = false
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    } catch (e: PhotoException) { throw e } catch (_: Exception) { throw PhotoException("Couldn't read this image.") }

    /** API 26–27: bounds, `inSampleSize`, and the framework ExifInterface for orientation. */
    private fun decodeLegacy(bytes: ByteArray, maxSide: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o); checkSize(o.outWidth, o.outHeight)
        var sample = 1; while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw PhotoException("Couldn't read this image.")
        val rot = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        }.getOrDefault(0)
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val s = min(1.0, maxSide.toDouble() / max(bmp.width, bmp.height))
        return if (s < 1) Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true) else bmp
    }

    fun analysisCopy(master: Bitmap): PaletteGenerator.Image {
        val s = PaletteGenerator.ANALYSIS_SIZE.toDouble() / max(master.width, master.height)
        val w = max(1, (master.width * min(1.0, s)).roundToInt()); val h = max(1, (master.height * min(1.0, s)).roundToInt())
        val small = Bitmap.createScaledBitmap(master, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== master) small.recycle()
        return PaletteGenerator.Image(px, w, h)
    }
}

/**
 * Saving a created theme: an ordinary record revision whose background 0 is the owner's master image, so apply, Home,
 * gallery, detail and the zone solver are exactly the H5.1 paths. Edit saves a new revision with the same id.
 */
class CreatedThemes(private val store: ThemeStore, private val files: ThemeFiles) {
    /** Saves 1–4 images, each with its own composition from [recipe].framings, and one palette for the set. */
    fun save(existingId: String?, name: String, palette: OmarchyPalette, recipe: CreateRecipe, photos: List<Photo>, displayW: Int, displayH: Int): Outcome {
        require(photos.size in 1..no.heimflyt.launcher.theme.store.MAX_THEME_IMAGES)
        val id = existingId ?: "created:${UUID.randomUUID()}"
        val request = store.beginRecordRequest(id)
        val previous = if (existingId != null) store.loadRecord(existingId)
            ?: return Outcome.Failed("Couldn't read the existing theme.") else null
        val previousPin = store.pin(previous?.dir)
        val (stage, pin) = try { store.newStaging() } catch (_: IOException) { previousPin.close(); return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        try {
            val stored = photos.mapIndexed { i, photo ->
                val framing = recipe.framings.getOrElse(i) { Framing() }
                // The master was already re-encoded from pixels only; it is copied as is (never the picked file).
                val masterBytes = files.read(photo.masterFile, 128 * 1024 * 1024)
                files.write(File(stage, "master-$i.webp"), masterBytes)
                val tw = 360; val th = (tw.toLong() * displayH / displayW).toInt()
                val thumb = Images.crop(photo.preview, tw, th, framing)
                files.write(File(stage, "thumb-$i.webp"), Images.webp(thumb, 85)); thumb.recycle()
                val crop = Images.cropRect(photo.masterW, photo.masterH, displayW, displayH, framing)
                // Soft only if Home's prepared image (0.75× display width) would upscale this crop more than 1.6×.
                val outH = (minOf(displayW, 1440) * 0.75f * displayH / displayW)
                StoredBackground("master-$i.webp", "thumb-$i.webp", if (photos.size == 1) name else "$name ${i + 1}", photo.masterW, photo.masterH,
                    ThemeStore.sha256(masterBytes), outH > 1.6f * crop.height())
            }
            val variants = previous?.let { old ->
                old.record.desktopVariants.filter { v ->
                    old.record.backgrounds.getOrNull(v.index)?.sha256 == stored.getOrNull(v.index)?.sha256
                }.map { v ->
                    files.write(File(stage, v.file), files.read(File(old.dir, v.file), 64 * 1024 * 1024))
                    v
                }
            }.orEmpty()
            files.write(File(stage, "colors.toml"), ColorsToml.write(palette).toByteArray())
            val record = ThemeRecord(id, name, ThemeOrigin.Created(recipe, System.currentTimeMillis()), palette.light, PaletteSource.GENERATED,
                stored, null, null, emptyList(), System.currentTimeMillis(), desktopVariants = variants)
            files.write(File(stage, "manifest.json"), record.toJson().toString().toByteArray())
            return store.commitRecord(stage, id, request, previous?.rev)
        } catch (_: IOException) { return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        finally { pin.close(); previousPin.close(); if (stage.exists()) runCatching { files.deleteTree(stage) } }
    }
}

/** Persists one owner-approved desktop image in a new immutable record revision. Home never reads this file. */
class DesktopVariants(private val store: ThemeStore, private val files: ThemeFiles) {
    fun attach(record: no.heimflyt.launcher.theme.store.LoadedRecord, index: Int, bitmap: Bitmap): Outcome = change(record, index, bitmap)

    fun remove(record: no.heimflyt.launcher.theme.store.LoadedRecord, index: Int): Outcome = change(record, index, null)

    private fun change(record: no.heimflyt.launcher.theme.store.LoadedRecord, index: Int, bitmap: Bitmap?): Outcome {
        if (record.record.origin !is ThemeOrigin.Created || index !in record.record.backgrounds.indices || record.dir == null ||
            (bitmap != null && (bitmap.width !in 1..4096 || bitmap.height !in 1..4096))) return Outcome.Failed("Couldn't use this desktop image.")
        val request = store.beginRecordRequest(record.record.id)
        val oldPin = store.pin(record.dir)
        val (stage, stagePin) = try { store.newStaging() } catch (_: IOException) { oldPin.close(); return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        try {
            for (bg in record.record.backgrounds) {
                files.write(File(stage, bg.file), files.read(File(record.dir, bg.file), 64 * 1024 * 1024))
                files.write(File(stage, bg.thumb), files.read(File(record.dir, bg.thumb), 64 * 1024 * 1024))
            }
            files.write(File(stage, "colors.toml"), files.read(File(record.dir, "colors.toml"), ColorsToml.MAX_BYTES))
            val variant = bitmap?.let {
                val file = "desktop-$index.jpg"
                val bytes = ExportImages.backgroundJpeg(it)
                files.write(File(stage, file), bytes)
                DesktopVariant(index, file, ThemeStore.sha256(bytes), it.width, it.height)
            }
            for (v in record.record.desktopVariants.filter { it.index != index }) {
                files.write(File(stage, v.file), files.read(File(record.dir, v.file), 64 * 1024 * 1024))
            }
            val updated = record.record.copy(desktopVariants = record.record.desktopVariants.filter { it.index != index } + listOfNotNull(variant))
            files.write(File(stage, "manifest.json"), updated.toJson().toString().toByteArray())
            return store.commitRecord(stage, updated.id, request, record.rev)
        } catch (_: Exception) { return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        finally { stagePin.close(); oldPin.close(); if (stage.exists()) runCatching { files.deleteTree(stage) } }
    }
}

/** Android half of the portable export: source-quality JPEG from the master (no metadata) and a rendered preview.png. */
object ExportImages {
    fun backgroundJpeg(master: Bitmap): ByteArray = ByteArrayOutputStream().also { master.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()

    /** A 16:9 desktop crop around the focal point, never enlarged and capped at 3840×2160. */
    fun landscape(master: Bitmap, framing: Framing): Bitmap {
        val r = Images.cropRect(master.width, master.height, 16, 9, Framing(framing.fx, framing.fy, 1f))
        val crop = Bitmap.createBitmap(master, r.left, r.top, r.width(), r.height())
        if (crop.width <= 3840 && crop.height <= 2160) return crop
        val scale = min(3840.0 / crop.width, 2160.0 / crop.height)
        return Bitmap.createScaledBitmap(crop, max(1, (crop.width * scale).roundToInt()), max(1, (crop.height * scale).roundToInt()), true)
            .also { if (crop !== master) crop.recycle() }
    }

    /** A 1600×900 landscape card: the photo (centred on the framing) with the palette as a strip and the theme's name. */
    fun previewPng(master: Bitmap, palette: OmarchyPalette, name: String, framing: Framing): ByteArray {
        val w = 1600; val h = 900
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        fun col(k: String) = (palette[k] ?: 0) or (0xff shl 24)
        c.drawColor(col("background"))
        val src = Images.cropRect(master.width, master.height, w, h - 160, Framing(framing.fx, framing.fy, 1f))
        c.drawBitmap(master, src, Rect(0, 0, w, h - 160), Paint(Paint.FILTER_BITMAP_FLAG))
        val keys = listOf("background", "lighter_background", "foreground", "accent", "red", "yellow", "green", "cyan", "blue", "magenta")
        val sw = w / keys.size.toFloat()
        keys.forEachIndexed { i, k -> c.drawRect(RectF(i * sw, h - 160f, (i + 1) * sw, h - 100f), Paint().apply { color = col(k) }) }
        c.drawRect(0f, h - 100f, w.toFloat(), h.toFloat(), Paint().apply { color = col("background") })
        c.drawText(name, 48f, h - 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col("foreground"); textSize = 48f; typeface = Typeface.DEFAULT })
        c.drawText("made with Flyt", w - 48f, h - 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col("accent"); textSize = 30f; textAlign = Paint.Align.RIGHT; typeface = Typeface.MONOSPACE })
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { out.recycle() }
    }

    /**
     * Every image of the theme: its desktop crop around its own focal point (`backgrounds/`, Omarchy's rotation).
     * One master is decoded at a time; editing masters are never exported to this target.
     */
    fun write(out: OutputStream, name: String, palette: OmarchyPalette, masters: List<File>, framings: List<Framing>, variants: Map<Int, File> = emptyMap()) {
        require(masters.size in 1..4)
        val landscapes = ArrayList<ByteArray>(); var preview: ByteArray? = null
        masters.forEachIndexed { i, file ->
            val master = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: throw IOException("unreadable")
            val framing = framings.getOrElse(i) { Framing() }
            val selected = variants[i]?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw IOException("unreadable desktop image") }
            val land = if (selected != null) landscape(selected, Framing()) else landscape(master, framing)
            if (i == 0) preview = if (selected != null) previewPng(selected, palette, name, Framing()) else previewPng(master, palette, name, framing)
            landscapes += backgroundJpeg(land); if (land !== master) land.recycle()
            if (selected != null && selected !== land) selected.recycle()
            master.recycle()
        }
        OmarchyExport.writeZip(out, OmarchyExport.entries(name, palette, landscapes, preview!!, variants.keys))
    }
}
