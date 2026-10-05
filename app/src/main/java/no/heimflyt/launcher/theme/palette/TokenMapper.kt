package no.heimflyt.launcher.theme.palette

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Opaque sRGB colours as 0xRRGGBB ints. Pure Kotlin so the contrast contract is JVM-testable. */
object Contrast {
    private fun channel(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    fun luminance(rgb: Int): Double =
        0.2126 * channel(rgb shr 16 and 0xff) + 0.7152 * channel(rgb shr 8 and 0xff) + 0.0722 * channel(rgb and 0xff)
    fun ratio(a: Int, b: Int): Double { val la = luminance(a); val lb = luminance(b); return (max(la, lb) + 0.05) / (min(la, lb) + 0.05) }
    /** Interpolates the encoded 8-bit channels and rounds, like Omarchy's mix_color. The result is already quantised. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun m(sh: Int) = ((a shr sh and 0xff) * (1 - t) + (b shr sh and 0xff) * t).roundToInt().coerceIn(0, 255)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
    /** Source-over composition of [fg] at [alpha] onto opaque [bg], quantised. */
    fun over(fg: Int, alpha: Double, bg: Int): Int = mix(bg, fg, alpha)
    const val BLACK = 0x000000
    const val WHITE = 0xffffff
    fun endpoint(ground: Int) = if (ratio(ground, BLACK) >= ratio(ground, WHITE)) BLACK else WHITE
}

/** Canonical Omarchy palette keys consumed by the mapper; other keys are ignored. */
data class OmarchyPalette(val colors: Map<String, Int>, val light: Boolean) {
    operator fun get(key: String): Int? = colors[key]
}

/** Every token of VISUAL_SYSTEM.md §2.0, quantised and validated. */
data class ResolvedColors(
    val ground: Int, val groundDeep: Int, val raised: Int, val selection: Int,
    val ink: Int, val inkStrong: Int, val inkMuted: Int, val control: Int, val hairline: Int,
    val accent: Int, val accentInk: Int, val onAccent: Int, val accentVeil: Int,
    val danger: Int, val dangerInk: Int, val onDanger: Int, val positive: Int, val caution: Int,
    val identity: List<Int>, val onIdentity: List<Int>, val scrim: Int, val endpoint: Int,
    val isLight: Boolean, val adjustments: List<String>,
) {
    val textBackdrops get() = listOf(ground, raised, selection, accentVeil)
}

/** The ordered derivation of VISUAL_SYSTEM.md §2.0. Each step depends only on earlier ones. */
object TokenMapper {
    const val TEXT = 4.5
    const val UI = 3.0
    const val STEP_CAP = 1.45
    val IDENTITY_KEYS = listOf("red", "orange", "yellow", "green", "cyan", "blue", "magenta")

    fun shiftToward(c: Int, e: Int, min: Double, against: List<Int>): Int {
        for (i in 0..20) {
            val x = Contrast.mix(c, e, i * 0.05)
            if (against.all { Contrast.ratio(x, it) >= min }) return x
        }
        return e
    }

    private fun firstPassing(fill: Int, candidates: List<Int>) =
        candidates.firstOrNull { Contrast.ratio(it, fill) >= TEXT }
            ?: if (Contrast.ratio(Contrast.BLACK, fill) >= Contrast.ratio(Contrast.WHITE, fill)) Contrast.BLACK else Contrast.WHITE

    /** Keeps a backdrop within [STEP_CAP] of ground by moving it back toward ground ([target] is used when it exceeds the cap). */
    private fun capStep(ground: Int, x: Int, target: Double): Int {
        if (Contrast.ratio(x, ground) <= STEP_CAP) return x
        var t = 1.0
        while (t > 0.0) { val c = Contrast.mix(ground, x, t); if (Contrast.ratio(c, ground) <= target) return c; t -= 0.05 }
        return ground
    }

    fun map(p: OmarchyPalette): ResolvedColors {
        val notes = mutableListOf<String>()
        val bg = p["background"] ?: error("background required")
        val fg = p["foreground"] ?: error("foreground required")
        // 1. ground + invariant G, recomputing the endpoint for every quantised candidate.
        var ground = bg
        var e = Contrast.endpoint(ground)
        if (Contrast.ratio(ground, e) < 7.0) {
            val side = if (p.light) Contrast.WHITE else Contrast.BLACK
            for (i in 1..20) {
                val c = Contrast.mix(bg, side, i * 0.05)
                val ce = Contrast.endpoint(c)
                if (Contrast.ratio(c, ce) >= 7.0 || i == 20) { ground = c; e = ce; break }
            }
            notes += "ground shifted for contrast headroom"
        }
        // 2. text backdrops within the step cap.
        val lighter = p["lighter_background"] ?: ground
        val rawRaisedRatio = Contrast.ratio(lighter, ground)
        val raised = when {
            rawRaisedRatio < 1.08 -> capStep(ground, Contrast.mix(ground, fg, 0.07), 1.30)
            rawRaisedRatio > STEP_CAP -> capStep(ground, lighter, 1.30).also { notes += "raised softened" }
            else -> lighter
        }
        val selection = capStep(ground, p["selection"] ?: raised, 1.30)
        val accent0 = p["accent"] ?: p["blue"] ?: p["color4"] ?: fg
        var veil = Contrast.mix(ground, accent0, 0.16)
        var vt = 0.16
        while (Contrast.ratio(veil, ground) > STEP_CAP && vt > 0.0) { vt -= 0.01; veil = Contrast.mix(ground, accent0, vt) }
        val backdrops = listOf(ground, raised, selection, veil)
        // 3-6. text inks.
        val ink = shiftToward(fg, e, TEXT, backdrops)
        val strongSource = listOfNotNull(p["bright_foreground"], fg).maxBy { Contrast.ratio(it, ground) }
        val inkStrong = shiftToward(strongSource, e, TEXT, backdrops)
        var inkMuted = ink
        for (step in 9 downTo 1) {
            val c = Contrast.mix(ink, ground, step * 0.05)
            if (backdrops.all { Contrast.ratio(c, it) >= TEXT }) { inkMuted = c; break }
        }
        // 7-9. accent family.
        val surfaces = listOf(ground, raised)
        val accent = shiftToward(accent0, e, UI, surfaces)
        val accentInk = shiftToward(accent0, e, TEXT, backdrops)
        val onAccent = firstPassing(accent, listOf(ground, inkStrong))
        // 10. status colours.
        val red = p["red"] ?: accent0
        val danger = shiftToward(red, e, UI, surfaces)
        val dangerInk = shiftToward(red, e, TEXT, backdrops)
        val positive = shiftToward(p["green"] ?: accent0, e, UI, surfaces)
        val caution = shiftToward(p["yellow"] ?: accent0, e, UI, surfaces)
        // 11. identity colours for monograms and swatches.
        val identity = IDENTITY_KEYS.map { shiftToward(p[it] ?: accent0, e, UI, surfaces) }
        val onIdentity = identity.map { firstPassing(it, listOf(ground, inkStrong)) }
        // 12. decorative.
        val groundDeep = if (p.light) ground else (p["darker_background"] ?: Contrast.mix(ground, Contrast.BLACK, 0.5))
        val muted = p["muted"]
        val hairline = if (muted != null && Contrast.ratio(muted, ground) in 1.25..3.0) muted else Contrast.mix(ink, ground, 0.84)
        return ResolvedColors(
            ground, groundDeep, raised, selection, ink, inkStrong, inkMuted, inkMuted, hairline,
            accent, accentInk, onAccent, veil, danger, dangerInk, firstPassing(danger, emptyList()), positive, caution,
            identity, onIdentity, groundDeep, e, p.light, notes,
        )
    }
}

/** The token-only rendered pairs of VISUAL_SYSTEM.md §2.3 on stored values. Apply aborts on any violation (THEME_ARCHITECTURE.md §8.1). */
fun ResolvedColors.violations(): List<String> {
    val out = mutableListOf<String>()
    fun need(min: Double, fg: Int, bg: Int, what: String) { if (Contrast.ratio(fg, bg) < min - 1e-9) out += "%s #%06x on #%06x".format(what, fg, bg) }
    if (Contrast.ratio(ground, endpoint) < 7.0) out += "invariant G"
    for (b in listOf(raised, selection, accentVeil)) if (Contrast.ratio(b, ground) > TokenMapper.STEP_CAP + 1e-9) out += "backdrop step #%06x".format(b)
    for (bg in textBackdrops) {
        need(4.5, ink, bg, "ink"); need(4.5, inkStrong, bg, "inkStrong"); need(4.5, inkMuted, bg, "inkMuted")
        need(4.5, accentInk, bg, "accentInk"); need(4.5, dangerInk, bg, "dangerInk"); need(3.0, control, bg, "control")
    }
    for (bg in listOf(ground, raised)) {
        need(3.0, accent, bg, "accent"); need(3.0, danger, bg, "danger"); need(3.0, positive, bg, "positive"); need(3.0, caution, bg, "caution")
        identity.forEachIndexed { i, c -> need(3.0, c, bg, "identity$i") }
    }
    need(4.5, onAccent, accent, "onAccent"); need(4.5, onDanger, danger, "onDanger")
    identity.forEachIndexed { i, c -> need(4.5, onIdentity[i], c, "onIdentity$i") }
    return out
}

/** Compiled fallback palette: Omarchy's Tokyo Night colors.toml (MIT), palette by enkia (MIT). */
object FallbackPalette {
    val krets = OmarchyPalette(mapOf(
        "accent" to 0x68d9d0, "selection" to 0x183c45, "muted" to 0x31545c,
        "background" to 0x101d25, "dark_background" to 0x0c171e, "darker_background" to 0x091219, "lighter_background" to 0x1b3038,
        "foreground" to 0xc8d8d5, "dark_foreground" to 0x829e9d, "light_foreground" to 0xe1eae4, "bright_foreground" to 0xf3f3e7,
        "red" to 0xf28d8d, "yellow" to 0xf0cb83, "orange" to 0xe9aa7e, "green" to 0x8dd7a0, "cyan" to 0x68d9d0,
        "blue" to 0x86b9ec, "magenta" to 0xc5a5de,
    ), light = false)
    val tokyoNight = OmarchyPalette(mapOf(
        "accent" to 0x7aa2f7, "selection" to 0x292e42, "muted" to 0x414868,
        "background" to 0x1a1b26, "dark_background" to 0x13141c, "darker_background" to 0x0e0e14, "lighter_background" to 0x24283b,
        "foreground" to 0xa9b1d6, "dark_foreground" to 0x565f89, "light_foreground" to 0xb4bee6, "bright_foreground" to 0xc0caf5,
        "red" to 0xf7768e, "yellow" to 0xe0af68, "orange" to 0xeb927b, "green" to 0x9ece6a, "cyan" to 0x449dab,
        "blue" to 0x7aa2f7, "magenta" to 0xad8ee6,
    ), light = false)
}
