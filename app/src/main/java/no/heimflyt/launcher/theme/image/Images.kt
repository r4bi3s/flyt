package no.heimflyt.launcher.theme.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.store.BackgroundKind
import no.heimflyt.launcher.theme.store.CancelledException
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.GenerationPreparer
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.StoredBackground
import no.heimflyt.launcher.theme.store.ThemeChoice
import no.heimflyt.launcher.theme.store.ThemeFiles
import no.heimflyt.launcher.theme.store.ThemeStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Resolution warnings from the actual crop: soft only if Home's prepared image would upscale the chosen source crop more
 * than 1.6×. Never inferred from the theme's origin (the old "desktop" wording mislabelled created themes).
 */
object Resolution {
    fun low(cropHeightPx: Int, sig: ZoneSignature) = BackgroundProcessor.outputSize(sig).second > 1.6f * cropHeightPx
}

/** Bounded platform decoding and re-encoding. Software bitmaps only; hardware allocation is for Home display alone. */
object Images {
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 40_000_000L

    fun webp(bmp: Bitmap, quality: Int = 90): ByteArray = ByteArrayOutputStream().also {
        @Suppress("DEPRECATION")
        bmp.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, quality, it)
    }.toByteArray()

    enum class Format { JPEG, PNG, WEBP }
    fun magic(b: ByteArray): Format? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> Format.JPEG
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> Format.PNG
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> Format.WEBP
        else -> null
    }

    /** Header-only bounds, then a sampled software decode whose short side stays ≥ [minShort] where the source allows. */
    fun decodeBounded(bytes: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val w = o.outWidth; val h = o.outHeight
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) return null
        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = false
        })
    }

    fun headerSize(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    /** The portrait crop window of [framing] over a [sw] × [sh] source for an output aspect of [w] / [h]. */
    fun cropRect(sw: Int, sh: Int, w: Int, h: Int, framing: Framing): Rect {
        val aspect = w.toFloat() / h
        val zoom = max(1f, framing.zoom)
        var cw: Float; var ch: Float
        if (sw.toFloat() / sh > aspect) { ch = sh / zoom; cw = ch * aspect } else { cw = sw / zoom; ch = cw / aspect }
        cw = min(cw, sw.toFloat()); ch = min(ch, sh.toFloat())
        val cx = (framing.fx * sw).coerceIn(cw / 2, sw - cw / 2)
        val cy = (framing.fy * sh).coerceIn(ch / 2, sh - ch / 2)
        return Rect((cx - cw / 2).roundToInt(), (cy - ch / 2).roundToInt(), (cx + cw / 2).roundToInt(), (cy + ch / 2).roundToInt())
    }

    fun crop(src: Bitmap, w: Int, h: Int, framing: Framing): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, cropRect(src.width, src.height, w, h, framing), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return out
    }

    fun pixels(bmp: Bitmap): IntArray { val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) px[i] = px[i] and 0xffffff; return px }
    fun bitmap(px: IntArray, w: Int, h: Int): Bitmap = Bitmap.createBitmap(IntArray(px.size) { px[it] or (0xff shl 24) }, w, h, Bitmap.Config.ARGB_8888)
}

/**
 * The background half of a generation (VISUAL_SYSTEM.md §8.2, R3): source (theme image or Ground) → portrait crop → strength
 * → zone solver bands → WebP → re-verification of the decoded stored pixels.
 */
class BackgroundProcessor(private val files: ThemeFiles, private val assets: android.content.res.AssetManager? = null) : GenerationPreparer {
    companion object {
        /**
         * VISUAL_SYSTEM.md §8.4 fallback, applied after measurement: a display-size hardware bitmap was counted twice in PSS
         * on pixel9-eivind (+31 MiB), so the prepared image is 0.75× the display width and scaled up by Home.
         */
        const val DISPLAY_SCALE = 0.75f
        fun outputSize(sig: ZoneSignature): Pair<Int, Int> {
            val w = (min(sig.widthPx, 1440) * DISPLAY_SCALE).toInt()
            return w to (w.toLong() * sig.heightPx / sig.widthPx).toInt()
        }
        fun seed(id: String) = id.hashCode()
    }

    /** Source pixels after crop and strength, before bands: shared by apply and the (unencoded) previews. */
    fun source(record: LoadedRecord, choice: ThemeChoice, tokens: ResolvedColors, w: Int, h: Int, density: Float): IntArray? {
        val bg = choice.background
        val bmp = when (bg.kind) {
            BackgroundKind.IMAGE -> {
                val stored = record.record.backgrounds.getOrNull(bg.index) ?: return null
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val src = if (record.record.id == "bundled:krets")
                    assets?.open(stored.file)?.use { BitmapFactory.decodeStream(it, null, options) }
                else record.dir?.let { BitmapFactory.decodeFile(File(it, stored.file).path, options) }
                if (src == null) return null
                Images.crop(src, w, h, choice.framing).also { src.recycle() }
            }
            BackgroundKind.DUSK, BackgroundKind.CONTOUR, BackgroundKind.KRETS -> Grounds.render(bg.kind, tokens, w, h, seed(record.record.id), density)
            BackgroundKind.PLAIN -> return null
        }
        val px = Images.pixels(bmp); bmp.recycle()
        if (bg.kind == BackgroundKind.IMAGE) for (i in px.indices) px[i] = ZoneSolver.composite(px[i], tokens.scrim, choice.strength.alpha)
        return px
    }

    override fun prepare(record: LoadedRecord, choice: ThemeChoice, tokens: ResolvedColors, signature: ZoneSignature, dir: File, cancelled: () -> Boolean,
                         out: String): Protection {
        val (w, h) = outputSize(signature)
        val px = source(record, choice, tokens, w, h, signature.density * w / signature.widthPx) ?: throw java.io.IOException("background unavailable")
        if (cancelled()) throw CancelledException()
        val solution = ZoneSolver.solve(px, w, h, HomeZones.compute(signature, tokens, w, h, HomeText.extents(signature)), tokens.scrim, HomeZones.feather(signature, w))
        ZoneSolver.bake(px, solution, tokens.scrim)
        if (cancelled()) throw CancelledException()
        val baked = Images.bitmap(px, w, h)
        val bytes = Images.webp(baked); baked.recycle()
        files.write(File(dir, out), bytes)
        // The gate: verify the zones on the stored pixels after quantisation and lossy encoding.
        val stored = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw java.io.IOException("encoded background unreadable")
        val storedPx = Images.pixels(stored); stored.recycle()
        return ZoneSolver.protection(storedPx, w, solution, tokens.isLight)
    }

    override fun check(file: File, signature: ZoneSignature): Boolean = Images.headerSize(file) == outputSize(signature)

    /** Create screen: the same crop → strength → bands pipeline straight from an in-memory photo (nothing is stored). */
    fun previewFrom(src: Bitmap, framing: Framing, strength: no.heimflyt.launcher.theme.store.Strength, tokens: ResolvedColors, signature: ZoneSignature, width: Int): Bitmap {
        val w = width; val h = (w.toLong() * signature.heightPx / signature.widthPx).toInt()
        val bmp = Images.crop(src, w, h, framing)
        val px = Images.pixels(bmp); bmp.recycle()
        for (i in px.indices) px[i] = ZoneSolver.composite(px[i], tokens.scrim, strength.alpha)
        val solution = ZoneSolver.solve(px, w, h, HomeZones.compute(signature, tokens, w, h, HomeText.extents(signature)), tokens.scrim, HomeZones.feather(signature, w))
        ZoneSolver.bake(px, solution, tokens.scrim)
        return Images.bitmap(px, w, h)
    }

    /** A small, unencoded preview with the same bands (theme cards and detail). Null for Plain. */
    fun preview(record: LoadedRecord, choice: ThemeChoice, tokens: ResolvedColors, signature: ZoneSignature, width: Int): Bitmap? {
        val w = width; val h = (w.toLong() * signature.heightPx / signature.widthPx).toInt()
        val px = source(record, choice, tokens, w, h, signature.density * w / signature.widthPx) ?: return null
        val solution = ZoneSolver.solve(px, w, h, HomeZones.compute(signature, tokens, w, h, HomeText.extents(signature)), tokens.scrim, HomeZones.feather(signature, w))
        ZoneSolver.bake(px, solution, tokens.scrim)
        return Images.bitmap(px, w, h)
    }
}

/**
 * The widest clock and date Home can draw for a signature's locale and week setting, measured with the same fonts
 * (display: sans-serif-light, tabular figures, 60 sp; date: default, 16 sp) at the signature's density and font scale.
 * Linear sp scaling overestimates Android 14+'s non-linear large fonts, so the zones stay conservative.
 */
object HomeText {
    fun extents(sig: ZoneSignature): TextExtents {
        val (locale, week) = HomeZones.textKey(sig.textKey)
        val sp = sig.density * sig.fontScale
        val clock = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            fontFeatureSettings = "tnum"; textSize = 60f * sp
        }
        val date = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT; textSize = 16f * sp }
        val cal = java.util.Calendar.getInstance(locale).apply { set(2028, 0, 1, 0, 59, 0) }
        var cw = 0f; var dw = 0f
        for (h in 0..23) { cal.set(java.util.Calendar.HOUR_OF_DAY, h); cw = max(cw, clock.measureText(no.heimflyt.launcher.ui.home.HomeDate.time(cal.time, locale))) }
        cal.set(2028, 0, 1)
        repeat(366) { dw = max(dw, date.measureText(no.heimflyt.launcher.ui.home.HomeDate.date(cal.time, locale, week))); cal.add(java.util.Calendar.DAY_OF_YEAR, 1) }
        return TextExtents(cw, dw)
    }
}

/** Import-time processing of accepted theme images (THEME_ARCHITECTURE.md §6); only processed outputs are written. */
object ThemeImages {
    class Result(val background: StoredBackground?, val note: String?)

    fun background(bytes: ByteArray, name: String, index: Int, displayW: Int, displayH: Int, dir: File, files: ThemeFiles): Result {
        if (Images.magic(bytes) == null) return Result(null, "skipped $name: not a JPEG, PNG or WebP image")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val sw = o.outWidth; val sh = o.outHeight
        if (sw <= 0 || sh <= 0) return Result(null, "skipped $name: unreadable image")
        if (sw > Images.MAX_SIDE || sh > Images.MAX_SIDE || sw.toLong() * sh > Images.MAX_PIXELS) return Result(null, "skipped $name: over 8192 px or 40 MP")
        // Height ≤ min(source, display height), long side ≤ 4096.
        val scale = minOf(1f, displayH.toFloat() / sh, 4096f / max(sw, sh))
        val tw = max(1, (sw * scale).roundToInt()); val th = max(1, (sh * scale).roundToInt())
        val decoded = Images.decodeBounded(bytes, tw, th) ?: return Result(null, "skipped $name: unreadable image")
        val bg = if (decoded.width == tw && decoded.height == th) decoded else Bitmap.createScaledBitmap(decoded, tw, th, true).also { decoded.recycle() }
        val file = "bg-$index.webp"; val thumb = "thumb-$index.webp"
        val bgBytes = Images.webp(bg)
        files.write(File(dir, file), bgBytes)
        val thumbW = 360; val thumbH = (thumbW.toLong() * displayH / displayW).toInt()
        val t = Images.crop(bg, thumbW, thumbH, Framing())
        files.write(File(dir, thumb), Images.webp(t, 85)); t.recycle()
        bg.recycle()
        // Portrait crop at display height upscaled more than 1.6× → soft on this phone.
        val low = displayH.toFloat() / sh > 1.6f
        return Result(StoredBackground(file, thumb, name.substringBeforeLast('.'), tw, th, ThemeStore.sha256(bgBytes), low),
            if (low) "$name: low resolution for this phone — may look soft" else null)
    }

    fun preview(bytes: ByteArray, dir: File, files: ThemeFiles): String? {
        if (Images.magic(bytes) != Images.Format.PNG) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outWidth > Images.MAX_SIDE || o.outHeight > Images.MAX_SIDE || o.outWidth.toLong() * o.outHeight > Images.MAX_PIXELS) return null
        val tw = min(1200, o.outWidth); val th = max(1, (o.outHeight.toLong() * tw / o.outWidth).toInt())
        val decoded = Images.decodeBounded(bytes, tw, th) ?: return null
        val p = if (decoded.width == tw) decoded else Bitmap.createScaledBitmap(decoded, tw, th, true).also { decoded.recycle() }
        files.write(File(dir, "preview.webp"), Images.webp(p, 85)); p.recycle()
        return "preview.webp"
    }
}
