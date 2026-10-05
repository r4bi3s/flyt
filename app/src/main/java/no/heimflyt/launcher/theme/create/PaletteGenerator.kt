package no.heimflyt.launcher.theme.create

import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** OKLab / OKLCH (Björn Ottosson's published formulas). Pure. */
object Oklab {
    private fun toLin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun toSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * max(c, 0.0).pow(1 / 2.4) - 0.055
    private val LIN = DoubleArray(256) { toLin(it / 255.0) }

    /** 0xRRGGBB → (L, a, b). */
    fun fromRgb(rgb: Int, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val r = LIN[rgb shr 16 and 0xff]; val g = LIN[rgb shr 8 and 0xff]; val b = LIN[rgb and 0xff]
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return out
    }

    private fun toRgbUnclamped(L: Double, a: Double, b: Double): DoubleArray {
        val l = (L + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (L - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (L - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
            toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
            toSrgb(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s))
    }

    /** OKLCH → 0xRRGGBB, gamut-mapped by reducing chroma at constant L and h (CREATE_THEME.md §6.3). */
    fun lch(L: Double, C: Double, hDeg: Double): Int {
        var c = C; val h = Math.toRadians(hDeg)
        var rgb = toRgbUnclamped(L.coerceIn(0.0, 1.0), c * cos(h), c * sin(h))
        var i = 0
        while (i++ < 40 && rgb.any { it < -1e-4 || it > 1 + 1e-4 }) { c *= 0.92; rgb = toRgbUnclamped(L.coerceIn(0.0, 1.0), c * cos(h), c * sin(h)) }
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (q(rgb[0]) shl 16) or (q(rgb[1]) shl 8) or q(rgb[2])
    }
}

enum class Variant(val id: String, val label: String) { CALM("calm", "Calm"), NATURAL("natural", "Natural"), BOLD("bold", "Bold"), KEEP("keep", "Keep") }
enum class ModeChoice { AUTO, DARK, LIGHT }

/** An owner-chosen accent is pinned by colour value, so it survives reframing (CREATE_THEME.md §5.3). */
sealed interface AccentChoice {
    data object Auto : AccentChoice
    data class Pinned(val rgb: Int) : AccentChoice
}

/** One extracted colour: OKLab centroid and weighted share. */
data class Cluster(val L: Double, val a: Double, val b: Double, val share: Double) {
    val C get() = hypot(a, b)
    val h get() = (Math.toDegrees(atan2(b, a)) + 360) % 360
    val rgb get() = Oklab.lch(L, C, h)
}

/** The framed analysis of one photo; regenerated whenever the framing changes (the palette follows the crop). */
class Analysis(val clusters: List<Cluster>, val salience: DoubleArray, val clockL: Double, val windowL: Double, val achromatic: Boolean,
               /** Auto light/dark (for a set, the majority of its images). */
               val autoLight: Boolean = clockL > 0.68 && windowL > 0.55)

/** A crop window in analysis pixels. */
data class Window(val x0: Int, val y0: Int, val w: Int, val h: Int)

/**
 * The local palette generator of CREATE_THEME.md §5.2 and §6.2–6.3: pure Kotlin, deterministic, no dependency. It is a
 * palette source only: it emits a complete canonical Omarchy palette (every key), which then goes through the one
 * TokenMapper like any imported theme.
 */
object PaletteGenerator {
    const val VERSION = 1
    const val ANALYSIS_SIZE = 256
    private const val BAND_TOP = 0.34; private const val BAND_BOTTOM = 0.78

    class Image(val px: IntArray, val w: Int, val h: Int) {
        val lab: Array<DoubleArray> by lazy { Array(px.size) { Oklab.fromRgb(px[it] and 0xffffff) } }
    }

    // ---- framing (§5.2) ----

    /** The window of the display [aspect] (w/h) at [zoom] centred on (fx, fy), clamped inside the image. */
    fun window(img: Image, aspect: Double, fx: Double, fy: Double, zoom: Double = 1.0): Window {
        var ww: Int; var hh: Int
        if (img.w.toDouble() / img.h > aspect) { hh = img.h; ww = (img.h * aspect).roundToInt() } else { ww = img.w; hh = (img.w / aspect).roundToInt() }
        ww = max(1, (ww / max(1.0, zoom)).roundToInt()); hh = max(1, (hh / max(1.0, zoom)).roundToInt())
        val x0 = ((fx * img.w) - ww / 2.0).roundToInt().coerceIn(0, img.w - ww)
        val y0 = ((fy * img.h) - hh / 2.0).roundToInt().coerceIn(0, img.h - hh)
        return Window(x0, y0, ww, hh)
    }

    /** Automatic interest-based framing; returns (fx, fy). Simple heuristics: manual reframe is the reliable fallback. */
    fun autoFrame(img: Image, aspect: Double): Pair<Double, Double> {
        val w = img.w; val h = img.h; val lab = img.lab
        val interest = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = (lab[y * w + min(w - 1, x + 1)][0] - lab[y * w + max(0, x - 1)][0]) / 2
            val gy = (lab[min(h - 1, y + 1) * w + x][0] - lab[max(0, y - 1) * w + x][0]) / 2
            interest[i] = hypot(gx, gy) + 0.5 * hypot(lab[i][1], lab[i][2])
        }
        // Box blur, radius 3, via a summed-area table.
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { row += interest[y * w + x]; sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row } }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int): Double { // exclusive ends
            val a = x0.coerceIn(0, w); val b = y0.coerceIn(0, h); val c = x1.coerceIn(0, w); val d = y1.coerceIn(0, h)
            return sat[d * (w + 1) + c] - sat[b * (w + 1) + c] - sat[d * (w + 1) + a] + sat[b * (w + 1) + a]
        }
        val full = window(img, aspect, 0.5, 0.5)
        val horizontal = full.w < w
        val span = if (horizontal) w - full.w else h - full.h
        if (span <= 0) return 0.5 to 0.5
        val total = rect(0, 0, w, h)
        var best = Double.NEGATIVE_INFINITY; var bestOff = span / 2
        for (i in 0..32) {
            val off = (span * i / 32.0).roundToInt()
            val x0 = if (horizontal) off else 0; val y0 = if (horizontal) 0 else off
            fun band(a: Double, z: Double) = rect(x0, y0 + (a * full.h).toInt(), x0 + full.w, y0 + (z * full.h).toInt())
            val s = band(BAND_TOP, BAND_BOTTOM) - 0.6 * band(0.0, BAND_TOP) - 0.3 * band(0.86, 1.0) -
                0.15 * abs(i - 16) / 16.0 * total * (full.w.toDouble() * full.h) / (w.toDouble() * h)
            if (s > best) { best = s; bestOff = off }
        }
        return if (horizontal) (bestOff + full.w / 2.0) / w to 0.5 else 0.5 to (bestOff + full.h / 2.0) / h
    }

    // ---- extraction (§6.2) ----

    fun analyse(img: Image, win: Window): Analysis = analyseSet(listOf(img to win))

    /**
     * One palette for a multi-image theme (canonical decision 2026-09-29): every image contributes equally (its weights are
     * normalised), each through its own composition window and subject band; k-means runs once over the combined set.
     * Light/dark is the majority of the images' own auto decisions, the first image breaking a tie.
     */
    fun analyseSet(parts: List<Pair<Image, Window>>): Analysis {
        require(parts.isNotEmpty())
        val lab = ArrayList<DoubleArray>(); val wts = ArrayList<Double>(); val inBand = ArrayList<Boolean>()
        val votes = ArrayList<Boolean>(); var clock0 = 0.5; var window0 = 0.5
        fun median(values: List<Double>) = values.sorted().let { if (it.isEmpty()) 0.5 else it[it.size / 2] }
        for ((k, part) in parts.withIndex()) {
            val (img, win) = part
            val bandTop = win.y0 + (BAND_TOP * win.h).toInt(); val bandBottom = win.y0 + (BAND_BOTTOM * win.h).toInt()
            val w = DoubleArray(img.px.size) { 1.0 }
            for (y in win.y0 until win.y0 + win.h) for (x in win.x0 until win.x0 + win.w) w[y * img.w + x] = if (y in bandTop until bandBottom) 4.0 else 2.0
            for (i in w.indices) if ((img.px[i] ushr 24) < 128) w[i] = 0.0   // ARGB input: alpha < 0.5 is ignored
            if (w.none { it > 0 }) w.fill(1.0)
            val total = w.sum()
            for (y in 0 until img.h) for (x in 0 until img.w) {
                val i = y * img.w + x
                lab += img.lab[i]; wts += w[i] / total; inBand += x in win.x0 until win.x0 + win.w && y in bandTop until bandBottom
            }
            val clock = ArrayList<Double>(); val all = ArrayList<Double>()
            for (y in win.y0 until win.y0 + win.h) for (x in win.x0 until win.x0 + win.w) {
                val L = img.lab[y * img.w + x][0]; all += L; if (y < win.y0 + BAND_TOP * win.h) clock += L
            }
            val c = median(clock); val m = median(all)
            if (k == 0) { clock0 = c; window0 = m }
            votes += c > 0.68 && m > 0.55
        }
        val labA = lab.toTypedArray(); val wA = wts.toDoubleArray(); val n = labA.size
        val cents = kmeans(labA, wA)
        val assign = IntArray(n) { nearest(labA[it], cents) }
        val wsum = wA.sum()
        val share = DoubleArray(cents.size); for (i in 0 until n) share[assign[i]] += wA[i] / wsum
        val subCount = DoubleArray(cents.size); val allCount = DoubleArray(cents.size); var sub = 0
        for (i in 0 until n) { allCount[assign[i]]++; if (inBand[i]) { subCount[assign[i]]++; sub++ } }
        val keep = cents.indices.filter { share[it] >= 0.005 }.sortedByDescending { share[it] }
        val clusters = keep.map { Cluster(cents[it][0], cents[it][1], cents[it][2], share[it]) }
        val salience = keep.map { ((subCount[it] / max(1, sub) + 1e-3) / (allCount[it] / n + 1e-3)).coerceIn(0.5, 3.0) }.toDoubleArray()
        val achromatic = clusters.none { it.C > 0.05 && it.share >= 0.01 }
        val lights = votes.count { it }
        val light = if (lights * 2 == votes.size) votes.first() else lights * 2 > votes.size
        return Analysis(clusters, salience, clock0, window0, achromatic, light)
    }

    private fun dist2(p: DoubleArray, c: DoubleArray): Double { val a = p[0] - c[0]; val b = p[1] - c[1]; val d = p[2] - c[2]; return a * a + b * b + d * d }
    private fun nearest(p: DoubleArray, cents: List<DoubleArray>): Int { var best = 0; var bd = Double.MAX_VALUE; for (j in cents.indices) { val d = dist2(p, cents[j]); if (d < bd) { bd = d; best = j } }; return best }

    /** k = 10, deterministic farthest-point initialisation from the weighted mean, ≤ 10 iterations or shift < 0.002. */
    private fun kmeans(lab: Array<DoubleArray>, wts: DoubleArray, k: Int = 10): List<DoubleArray> {
        val n = lab.size; val ws = wts.sum().takeIf { it > 0 } ?: 1.0
        val mean = DoubleArray(3); for (i in 0 until n) for (d in 0..2) mean[d] += lab[i][d] * wts[i] / ws
        val cents = ArrayList<DoubleArray>()
        var first = 0; var fd = Double.MAX_VALUE
        for (i in 0 until n) if (wts[i] > 0) { val d = dist2(lab[i], mean); if (d < fd) { fd = d; first = i } }
        cents += lab[first].copyOf()
        val minD = DoubleArray(n) { dist2(lab[it], cents[0]) }
        while (cents.size < k) {
            var far = 0; var farD = -1.0
            for (i in 0 until n) if (wts[i] > 0 && minD[i] > farD) { farD = minD[i]; far = i }
            if (farD <= 0) break
            cents += lab[far].copyOf()
            for (i in 0 until n) minD[i] = min(minD[i], dist2(lab[i], cents.last()))
        }
        repeat(10) {
            val sum = Array(cents.size) { DoubleArray(3) }; val wsum = DoubleArray(cents.size)
            for (i in 0 until n) { if (wts[i] <= 0) continue; val j = nearest(lab[i], cents); wsum[j] += wts[i]; for (d in 0..2) sum[j][d] += lab[i][d] * wts[i] }
            var shift = 0.0
            for (j in cents.indices) if (wsum[j] > 0) {
                val nc = DoubleArray(3) { sum[j][it] / wsum[j] }; shift = max(shift, sqrt(dist2(nc, cents[j]))); cents[j] = nc
            }
            if (shift < 0.002) return cents
        }
        return cents
    }

    // ---- roles and variants (§6.3) ----

    private fun hdist(a: Double, b: Double): Double { val d = abs(a - b) % 360; return min(d, 360 - d) }
    private fun circularMean(items: List<Pair<Double, Double>>, fallback: Double): Double {
        var sx = 0.0; var sy = 0.0
        for ((h, w) in items) { sx += cos(Math.toRadians(h)) * w; sy += sin(Math.toRadians(h)) * w }
        return if (hypot(sx, sy) < 1e-6) fallback else (Math.toDegrees(atan2(sy, sx)) + 360) % 360
    }

    /** Accent candidates (≤ 6, hue-separated ≥ 20°) as (hue, chroma); also the Customize swatches. */
    fun candidates(an: Analysis): List<Pair<Double, Double>> {
        val chrom = an.clusters.filter { it.C > 0.02 }
        if (an.achromatic) {
            val h0 = circularMean(an.clusters.map { it.h to it.share * it.C }, 255.0)
            return listOf(h0 to 0.08, (h0 + 150) % 360 to 0.08, (h0 + 210) % 360 to 0.08)
        }
        val hG = groundHue(an)
        val scored = an.clusters.indices.filter { an.clusters[it].C > 0.02 }.map { j ->
            val c = an.clusters[j]; c to c.C * sqrt(c.share) * an.salience[j] * (if (hdist(c.h, hG) > 35) 1.5 else 1.0)
        }.sortedByDescending { it.second }
        val out = ArrayList<Pair<Double, Double>>()
        for ((c, _) in scored) { if (out.all { hdist(it.first, c.h) >= 20 }) out += c.h to c.C; if (out.size == 6) break }
        return out.ifEmpty { if (chrom.isEmpty()) listOf(255.0 to 0.10) else listOf(chrom.first().h to chrom.first().C) }
    }

    fun groundHue(an: Analysis): Double {
        if (an.achromatic) return circularMean(an.clusters.map { it.h to it.share * it.C }, 255.0)
        val chrom = an.clusters.filter { it.C > 0.02 }
        return if (chrom.isEmpty()) candidatesHueFallback else circularMean(chrom.map { it.h to it.share }, candidatesHueFallback)
    }
    private const val candidatesHueFallback = 255.0

    private class V(val cmin: Double, val cmax: Double, val ld: Double, val ll: Double, val gc: Double, val gd: Double, val gl: Double, val ic: Double, val cScale: Double)
    private val PARAMS = mapOf(
        Variant.CALM to V(0.06, 0.11, 0.76, 0.52, 0.018, 0.20, 0.965, 0.012, 1.0),
        Variant.NATURAL to V(0.05, 0.16, 0.74, 0.50, 0.045, 0.22, 0.95, 0.020, 1.0),
        Variant.BOLD to V(0.12, 0.19, 0.80, 0.46, 0.010, 0.15, 0.985, 0.006, 1.25),
    )
    private val NAMED = listOf("red" to 27.0, "orange" to 55.0, "yellow" to 95.0, "green" to 145.0, "cyan" to 200.0, "blue" to 255.0, "magenta" to 330.0)

    /** The accent (hue, chroma) a variant picks before any override. */
    fun variantAccent(an: Analysis, v: Variant): Pair<Double, Double> {
        val cands = candidates(an)
        return when (v) {
            Variant.NATURAL -> an.clusters.firstOrNull { it.C > 0.04 && !an.achromatic }?.let { it.h to it.C } ?: cands.first()
            Variant.BOLD -> cands.maxBy { it.second }
            else -> cands.first()
        }
    }

    /** A complete canonical palette (all 25 keys + mode). [accent] Pinned uses the colour's hue/chroma at the variant's L rules. */
    fun generate(an: Analysis, variant: Variant, accent: AccentChoice, mode: ModeChoice): OmarchyPalette {
        require(variant != Variant.KEEP) { "Keep copies the active palette" }
        val p = PARAMS.getValue(variant)
        val light = when (mode) { ModeChoice.AUTO -> an.autoLight; ModeChoice.DARK -> false; ModeChoice.LIGHT -> true }
        val hG = groundHue(an)
        var (ah, ac) = variantAccent(an, variant)
        if (accent is AccentChoice.Pinned) { val lab = Oklab.fromRgb(accent.rgb); ah = (Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360; ac = hypot(lab[1], lab[2]) }
        ac = (ac * p.cScale).coerceIn(p.cmin, p.cmax)
        val aL = if (light) p.ll else p.ld
        val g = if (light) p.gl else p.gd
        val meanC = an.clusters.sumOf { it.C * it.share }
        val gc = if (variant == Variant.NATURAL) min(p.gc, meanC) else p.gc
        val c = LinkedHashMap<String, Int>()
        fun neutral(L: Double, chroma: Double = gc) = Oklab.lch(L, chroma, hG)
        if (!light) {
            c["darker_background"] = neutral(g - 0.07); c["dark_background"] = neutral(g - 0.035); c["background"] = neutral(g)
            c["lighter_background"] = neutral(g + 0.05); c["selection"] = neutral(g + 0.09); c["muted"] = neutral(g + 0.20)
            c["dark_foreground"] = neutral(0.58, p.ic); c["foreground"] = neutral(0.86, p.ic); c["light_foreground"] = neutral(0.90, p.ic); c["bright_foreground"] = neutral(0.95, p.ic)
        } else {
            c["background"] = neutral(g); c["dark_background"] = neutral(g - 0.03); c["darker_background"] = neutral(g - 0.06)
            c["lighter_background"] = neutral(g - 0.045); c["selection"] = neutral(g - 0.085); c["muted"] = neutral(0.72)
            c["dark_foreground"] = neutral(0.55, p.ic); c["light_foreground"] = neutral(0.32, p.ic); c["foreground"] = neutral(0.26, p.ic); c["bright_foreground"] = neutral(0.18, p.ic)
        }
        c["accent"] = Oklab.lch(aL, ac, ah)
        val namedC = ac.coerceIn(0.08, 0.15)
        for ((key, anchor) in NAMED) {
            val nudge = an.clusters.filter { it.C > 0.04 && hdist(it.h, anchor) <= 15 }.minByOrNull { hdist(it.h, anchor) }?.h ?: anchor
            c[key] = Oklab.lch(aL, namedC, nudge)
            if (key != "orange") c["bright_$key"] = Oklab.lch(aL + 0.06, namedC, nudge)
        }
        c["brown"] = OmarchyResolver.mixColor("#%06x".format(c.getValue("orange")), "#000000", 0.5).removePrefix("#").toInt(16)
        return OmarchyPalette(OmarchyResolver.CANONICAL.associateWith { c.getValue(it) }, light)
    }
}

/** Deterministic Norwegian landscape names from the accent hue and ground lightness (CREATE_THEME.md §7.1). */
object ThemeNames {
    fun suggest(palette: OmarchyPalette, achromatic: Boolean, taken: Collection<String>): String {
        val base = if (achromatic) (if (palette.light) "Snø" else "Skifer") else {
            val lab = Oklab.fromRgb(palette["accent"] ?: 0x7f7f7f)
            val h = (Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360
            when {
                h < 15 || h >= 345 -> "Rosenrot"; h < 45 -> "Glør"; h < 75 -> "Rav"; h < 115 -> "Bjørk"; h < 165 -> "Mose"
                h < 215 -> "Fjord"; h < 285 -> "Blåtime"; else -> "Lyng"
            }
        }
        val lower = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in lower) return base
        var n = 2; while ("$base $n".lowercase() in lower) n++
        return "$base $n"
    }

    /** 1–40 characters of letters, digits, spaces and -’'. ; trimmed. */
    fun clean(input: String): String? = input.trim().take(40).takeIf { s -> s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in " -’'." } }
}
