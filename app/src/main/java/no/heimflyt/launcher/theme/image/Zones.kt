package no.heimflyt.launcher.theme.image

import no.heimflyt.launcher.theme.palette.Contrast
import no.heimflyt.launcher.theme.palette.ResolvedColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The layout inputs an image background's readability bands are solved for (VISUAL_SYSTEM.md §8.2, THEME_ARCHITECTURE.md §8.5).
 * All insets are the window's system bars ∪ display cutout ∪ system gestures (never the IME), in px.
 */
/** v3 (owner feedback 2026-09-28): local soft shading around the actual text, feathered in 2D; no full-width bands or boxes. */
private const val SOLVER = "v5"

data class ZoneSignature(
    val fontScale: Float, val density: Float, val widthPx: Int, val heightPx: Int,
    val safeLeft: Int, val safeTop: Int, val safeRight: Int, val safeBottom: Int, val statusPx: Int, val navPx: Int,
    /** What Home's clock/date text depends on beyond size: locale and week number (`nb-NO|week`). */
    val textKey: String = "",
) {
    /** The solver version is part of the signature, so generations prepared by an older solver re-prepare once (§8.5). */
    fun encode() = listOf(fontScale, density, widthPx, heightPx, safeLeft, safeTop, safeRight, safeBottom, statusPx, navPx, SOLVER, textKey).joinToString(",")
    companion object {
        fun decode(s: String?): ZoneSignature? = runCatching {
            val p = s!!.split(","); require(p.size == 12 && p[10] == SOLVER)
            ZoneSignature(p[0].toFloat(), p[1].toFloat(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9].toInt(), p[11])
        }.getOrNull()
    }
}

/**
 * What Home must add over an image background for the generation's signature. Text zones that the baked bands could not
 * protect get an opaque `raised` backdrop; bar zones that failed verification get an opaque `ground` band at runtime.
 */
data class Protection(
    val clockBackdrop: Boolean = false, val cornerBackdrop: Boolean = false,
    val statusLight: Boolean = false, val navLight: Boolean = false,
    val statusBand: Boolean = false, val navBand: Boolean = false,
    /** Status spike: the Heimflyt status row's text (inkStrong) was not verified in the status zone, so it needs a backdrop. */
    val statusTextBackdrop: Boolean = true,
) {
    fun encode() = listOf(clockBackdrop, cornerBackdrop, statusLight, navLight, statusBand, navBand, statusTextBackdrop).joinToString(",")
    companion object {
        /** Everything protected: used whenever an image is shown without a verified record (signature change, transitions). */
        fun conservative(light: Boolean) = Protection(true, true, light, light, true, true, true)
        /** H5.1 generations carry six fields; their status text was never solved, so it keeps a backdrop. */
        fun decode(s: String?): Protection? = runCatching {
            val p = s!!.split(",").map { it.toBooleanStrict() }; require(p.size == 6 || p.size == 7)
            Protection(p[0], p[1], p[2], p[3], p[4], p[5], p.getOrElse(6) { true })
        }.getOrNull()
    }
}

/** Home's fixed layout constants, shared by HomeSurface and the zone solver so the solved zones are Home's actual zones (dp/sp). */
object HomeLayout {
    const val GUTTER = 20f
    const val CLOCK_TOP = 32f
    const val CLOCK_LINE_SP = 64f
    const val DATE_LINE_SP = 22f
    const val DATE_SP = 16f
    const val CORNER_PAD = 8f
    const val CORNER_MIN_W = 88f
    const val CORNER_MIN_H = 48f
    const val LABEL_LINE_SP = 20f
    const val LABEL_SP = 15f
    const val ZONE_PAD = 8f
    /** Conservative text extents: typical date strings are ≤ 32 characters; corner words ≤ 6. */
    const val DATE_CHARS = 32
    /** The clock's width in em of the display size: "12:45 PM" in tabular figures is ≈ 4.1 em. */
    const val CLOCK_EM = 4.4f
    const val DATE_EM = 0.55f
    /** Soft falloff of the local shading around each text zone. */
    const val FEATHER = 56f
    const val WORD_CHARS = 6
    const val CHAR_EM = 0.62f
}

/** Measured widths (window px) of the widest clock and date strings Home can show for the signature's text key. */
data class TextExtents(val clockPx: Float, val datePx: Float)

enum class ZoneKind { STATUS, STATUS_TEXT, CLOCK, DATE, CORNER, NAV }

/** A protected rectangle in image px, with the text/icon colours drawn in it and the pair minimum (VISUAL_SYSTEM.md §2.3). */
class Zone(val kind: ZoneKind, val left: Int, val top: Int, val right: Int, val bottom: Int, val colors: List<Int>, val min: Double)

object HomeZones {
    /** Android's light (dark-icon) and dark (white-icon) system bar appearances, approximated conservatively. */
    const val DARK_ICONS = 0x1f1f1f
    const val LIGHT_ICONS = 0xffffff

    /** Home's zones for [sig], scaled to an image of [w] × [h] px (the image covers the whole window). */
    /** The feather of the local shading in image px. */
    fun feather(sig: ZoneSignature, w: Int) = max(1, (HomeLayout.FEATHER * sig.density * w / sig.widthPx).toInt())

    /** Parses the signature's text key into (locale, week number). */
    fun textKey(key: String): Pair<java.util.Locale, Boolean> =
        java.util.Locale.forLanguageTag(key.substringBefore('|').ifEmpty { "und" }) to key.endsWith("|week")

    fun compute(sig: ZoneSignature, t: ResolvedColors, w: Int, h: Int, text: TextExtents? = null): List<Zone> {
        val sx = w.toFloat() / sig.widthPx; val sy = h.toFloat() / sig.heightPx
        val d = sig.density; val sp = d * sig.fontScale
        val pad = HomeLayout.ZONE_PAD * d
        val out = mutableListOf<Zone>()
        fun zone(kind: ZoneKind, l: Float, tp: Float, r: Float, b: Float, colors: List<Int>, minimum: Double) {
            val zl = ((l - pad) * sx).toInt().coerceIn(0, w); val zr = ceil((r + pad) * sx).toInt().coerceIn(0, w)
            val zt = ((tp - pad) * sy).toInt().coerceIn(0, h); val zb = ceil((b + pad) * sy).toInt().coerceIn(0, h)
            if (zr > zl && zb > zt) out += Zone(kind, zl, zt, zr, zb, colors, minimum)
        }
        val W = sig.widthPx.toFloat(); val H = sig.heightPx.toFloat()
        if (sig.statusPx > 0) {
            zone(ZoneKind.STATUS, 0f, 0f, W, sig.statusPx.toFloat(), listOf(DARK_ICONS, LIGHT_ICONS), 3.0)
            // The same band as theme-coloured text (status spike): verified like any other text zone.
            zone(ZoneKind.STATUS_TEXT, 0f, 0f, W, sig.statusPx.toFloat(), listOf(t.inkStrong), 4.5)
        }
        if (sig.navPx > 0) zone(ZoneKind.NAV, 0f, H - sig.navPx, W, H, listOf(DARK_ICONS, LIGHT_ICONS), 3.0)
        val left = sig.safeLeft + HomeLayout.GUTTER * d; val right = W - sig.safeRight - HomeLayout.GUTTER * d
        val clockTop = sig.safeTop + HomeLayout.CLOCK_TOP * d
        val clockBottom = clockTop + HomeLayout.CLOCK_LINE_SP * sp
        // The actual text extents (conservative), not the full width: shading stays local to the words.
        val clockRight = minOf(right, left + (text?.clockPx?.plus(4 * d) ?: (HomeLayout.CLOCK_EM * 60f * sp)))
        zone(ZoneKind.CLOCK, left, clockTop, clockRight, clockBottom, listOf(t.inkStrong), 3.0)
        val dateW = text?.datePx?.plus(4 * d) ?: (HomeLayout.DATE_CHARS * HomeLayout.DATE_EM * HomeLayout.DATE_SP * sp)
        val dateLines = max(1, ceil(dateW / max(1f, right - left)).toInt())
        zone(ZoneKind.DATE, left, clockBottom, minOf(right, left + dateW), clockBottom + dateLines * HomeLayout.DATE_LINE_SP * sp, listOf(t.inkMuted), 4.5)
        val wordW = max(HomeLayout.CORNER_MIN_W * d, HomeLayout.WORD_CHARS * HomeLayout.CHAR_EM * HomeLayout.LABEL_SP * sp)
        val wordH = max(HomeLayout.CORNER_MIN_H * d, HomeLayout.LABEL_LINE_SP * sp)
        val rowBottom = H - sig.safeBottom - HomeLayout.CORNER_PAD * d
        val rowLeft = sig.safeLeft + HomeLayout.CORNER_PAD * d; val rowRight = W - sig.safeRight - HomeLayout.CORNER_PAD * d
        zone(ZoneKind.CORNER, rowLeft, rowBottom - wordH, rowLeft + wordW, rowBottom, listOf(t.inkStrong), 4.5)
        zone(ZoneKind.CORNER, rowRight - wordW, rowBottom - wordH, rowRight, rowBottom, listOf(t.inkStrong), 4.5)
        // The learning-state "Tune" word is not a solved zone (it is usually absent); over an image it gets its own backdrop.
        return out
    }
}

/**
 * The composited-zone solver of VISUAL_SYSTEM.md §8.2 (R3), on opaque 0xRRGGBB pixels. Pure, so it is tested on
 * checkerboards, tiny patches and uniform images. The worst pixel is every pixel of the zone (no percentile, no sampling).
 */
object ZoneSolver {
    /**
     * Owner feedback (2026-09-28): opaque boxes behind the clock and corner words looked wrong. A text zone may now take
     * the scrim up to 1.0 (solid at the text rows, eased outside), which always meets its pair; the box remains only as a
     * runtime fallback (signature change, unverified stored pixels). Deviation from VISUAL_SYSTEM.md §8.2's 0.85 cap.
     */
    const val MAX_ALPHA = 1.0
    const val STEP = 0.05
    /** Solved with headroom so lossy encoding rarely defeats a band; the stored pixels are re-verified without it. */
    const val MARGIN = 1.06
    private val LIN = DoubleArray(256) { val v = it / 255.0; if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }

    fun lum(rgb: Int) = 0.2126 * LIN[rgb shr 16 and 0xff] + 0.7152 * LIN[rgb shr 8 and 0xff] + 0.0722 * LIN[rgb and 0xff]
    private fun ok(l: Double, lt: Double, need: Double) = (max(l, lt) + 0.05) / (min(l, lt) + 0.05) >= need

    fun composite(p: Int, scrim: Int, a: Double): Int {
        if (a <= 0.0) return p and 0xffffff
        fun c(sh: Int) = ((p shr sh and 0xff) * (1 - a) + (scrim shr sh and 0xff) * a).roundToInt().coerceIn(0, 255)
        return (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    /** True if every pixel of [z], composited with [scrim] at [a], meets [need] against [fg]. */
    fun passes(px: IntArray, w: Int, z: Zone, fg: Int, scrim: Int, a: Double, need: Double): Boolean {
        val lt = lum(fg)
        for (y in z.top until z.bottom) { val row = y * w; for (x in z.left until z.right) if (!ok(lum(composite(px[row + x], scrim, a)), lt, need)) return false }
        return true
    }

    class Band(val zone: Zone, val alpha: Double?, val fg: Int)
    /** Per-pixel scrim alpha: local shading around each zone, feathered in both directions. */
    class Solution(val mask: FloatArray, val bands: List<Band>)

    /** Smallest alpha per zone; bar zones pick the icon appearance that needs less. Unmet text zones get no band (backdrop instead). */
    fun solve(px: IntArray, w: Int, h: Int, zones: List<Zone>, scrim: Int, feather: Int = max(1, w / 24)): Solution {
        val bands = zones.map { z ->
            // The status-row text is only verified (spike); it never adds shading to the photo.
            if (z.kind == ZoneKind.STATUS_TEXT) return@map Band(z, if (passes(px, w, z, z.colors.first(), scrim, 0.0, z.min * MARGIN)) 0.0 else null, z.colors.first())
            val options = z.colors.map { fg -> fg to alphas().firstOrNull { passes(px, w, z, fg, scrim, it, z.min * MARGIN) } }
            val best = options.filter { it.second != null }.minByOrNull { it.second!! }
            if (best != null) Band(z, best.second, best.first)
            else if (z.kind == ZoneKind.STATUS || z.kind == ZoneKind.NAV) {
                // Bars: a solid scrim band, with the icon appearance that reads on it.
                Band(z, 1.0, z.colors.maxBy { Contrast.ratio(it, scrim) })
            } else Band(z, null, z.colors.first())
        }
        val mask = FloatArray(w * h)
        for (b in bands) {
            val a = b.alpha ?: continue
            // Owner decision (2026-09-28, solver v5): the photo is never shaded behind Heimflyt's own text; the words carry
            // a glyph shadow on Home instead. Only Android's status/nav icon zones, which Heimflyt can't style, are shaded.
            if (a <= 0.0 || (b.zone.kind != ZoneKind.STATUS && b.zone.kind != ZoneKind.NAV)) continue
            val z = b.zone
            // Full strength inside the zone; a smooth falloff over [feather] px outside it, in every direction.
            for (y in max(0, z.top - feather) until minOf(h, z.bottom + feather)) {
                val dy = if (y < z.top) z.top - y else if (y >= z.bottom) y - z.bottom + 1 else 0
                for (x in max(0, z.left - feather) until minOf(w, z.right + feather)) {
                    val dx = if (x < z.left) z.left - x else if (x >= z.right) x - z.right + 1 else 0
                    val d = if (dx == 0 && dy == 0) 0.0 else kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                    if (d >= feather) continue
                    val v = (a * smooth(1.0 - d / feather)).toFloat()
                    val i = y * w + x
                    if (v > mask[i]) mask[i] = v
                }
            }
        }
        return Solution(mask, bands)
    }

    fun bake(px: IntArray, solution: Solution, scrim: Int) {
        val m = solution.mask
        for (i in px.indices) { val a = m[i]; if (a > 0f) px[i] = composite(px[i], scrim, a.toDouble()) }
    }

    /** Verifies the stored (decoded) pixels with no scrim added and no margin: the gate of VISUAL_SYSTEM.md §8.2. */
    fun protection(stored: IntArray, w: Int, solution: Solution, light: Boolean): Protection {
        fun verified(b: Band) = b.alpha != null && passes(stored, w, b.zone, b.fg, 0, 0.0, b.zone.min)
        val text = solution.bands.filter { it.zone.kind != ZoneKind.STATUS && it.zone.kind != ZoneKind.NAV && it.zone.kind != ZoneKind.STATUS_TEXT }
        val statusText = solution.bands.firstOrNull { it.zone.kind == ZoneKind.STATUS_TEXT }
        val status = solution.bands.firstOrNull { it.zone.kind == ZoneKind.STATUS }
        val nav = solution.bands.firstOrNull { it.zone.kind == ZoneKind.NAV }
        val clockOk = text.filter { it.zone.kind == ZoneKind.CLOCK || it.zone.kind == ZoneKind.DATE }.all(::verified)
        val cornerOk = text.filter { it.zone.kind == ZoneKind.CORNER }.all(::verified)
        val statusOk = status == null || verified(status)
        val navOk = nav == null || verified(nav)
        // With a runtime ground band, bars follow ground's appearance (light theme → dark icons).
        fun lightIcons(b: Band?, ok: Boolean) = if (b == null || !ok) light else b.fg == HomeZones.DARK_ICONS
        // Text zones are not shaded (v5), so they never ask for a box; their readability comes from the glyph shadow.
        @Suppress("UNUSED_VARIABLE") val measured = clockOk to cornerOk
        return Protection(false, false, lightIcons(status, statusOk), lightIcons(nav, navOk), !statusOk, !navOk,
            statusText == null || !verified(statusText))
    }

    private fun alphas() = (0..(MAX_ALPHA / STEP).roundToInt()).map { it * STEP }
    private fun smooth(x: Double) = x * x * (3 - 2 * x)
}
