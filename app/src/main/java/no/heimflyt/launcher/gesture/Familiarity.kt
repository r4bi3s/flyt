package no.heimflyt.launcher.gesture

import kotlin.math.roundToInt

/**
 * Local familiarity of one binding at one radial position. [level] is the stored guidance:
 * 2 full, 1 hinted, 0 minimal. [key] identifies binding + direction; a different key means fresh learning.
 */
data class SlotFamiliarity(val key: String = "", val level: Int = Familiarity.FULL, val streak: Int = 0)

/**
 * Deterministic progressive-invisibility rule. No model, timer or background work: state changes only
 * when a finished gesture is recorded.
 */
object Familiarity {
    const val FULL = 2
    const val HINTED = 1
    const val MINIMAL = 0

    /** Direction identity: centre angle and width in whole degrees. Position, radius, timing and activation mode are excluded. */
    fun directionKey(slot: Int, p: TuningParams): String {
        val s = p.safe()
        if (slot !in 0 until s.sectorCount) return "hidden"
        return "${RadialGeometry.sectorAngle(slot, s).roundToInt() % 360}/${(s.arcSpan / s.sectorCount).roundToInt()}"
    }

    fun reconcile(current: List<SlotFamiliarity>, keys: List<String>): List<SlotFamiliarity> =
        keys.mapIndexed { i, key -> current.getOrNull(i)?.takeIf { it.key == key } ?: SlotFamiliarity(key) }

    /** A clean dispatch extends the streak; stored guidance only ever decreases through streak thresholds. */
    fun record(s: SlotFamiliarity, clean: Boolean, p: TuningParams): SlotFamiliarity {
        if (!clean) return s.copy(level = (s.level + 1).coerceAtMost(FULL), streak = 0)
        val t = p.safe()
        val streak = (s.streak + 1).coerceAtMost(9999)
        val earned = when {
            streak >= t.minimalAfter -> MINIMAL
            streak >= t.hintAfter -> HINTED
            else -> FULL
        }
        return s.copy(level = minOf(s.level, earned), streak = streak)
    }

    /** Hesitation escalation during a gesture raises every slot temporarily; stored levels are unchanged. */
    fun shown(stored: Int, escalation: Int): Int = (stored + escalation).coerceIn(MINIMAL, FULL)

    fun name(level: Int) = when (level) { MINIMAL -> "Minimal"; HINTED -> "Hinted"; else -> "Full" }
}
