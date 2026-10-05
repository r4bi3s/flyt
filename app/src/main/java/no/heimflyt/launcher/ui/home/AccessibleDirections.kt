package no.heimflyt.launcher.ui.home

import no.heimflyt.launcher.HomeAction
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.label
import no.heimflyt.launcher.ui.components.directionWord
import java.util.concurrent.atomic.AtomicInteger

/**
 * The accessibility snapshot (SURFACES.md §2.5): built only from loaded settings, separate from the gesture snapshot.
 * Every snapshot allocates fresh action ids, so an obsolete accessibility node can never invoke a newly bound slot.
 */
class AccessibleDirections private constructor(val actions: List<Action>) {
    data class Action(val id: Int, val slot: Int, val action: HomeAction, val label: String)

    fun find(id: Int): Action? = actions.firstOrNull { it.id == id }
    fun owns(id: Int) = id in ID_BASE until ID_BASE + ID_SPAN

    companion object {
        /** Outside the framework's standard action ids (small bit flags and android:id/accessibilityAction* values). */
        const val ID_BASE = 0x5A000000
        const val ID_SPAN = 0x00ffffff
        private val next = AtomicInteger(0)
        fun isOurs(id: Int) = id in ID_BASE until ID_BASE + ID_SPAN

        fun from(bindings: List<HomeAction>, tuning: TuningParams): AccessibleDirections {
            val p = tuning.safe()
            val list = (0 until p.sectorCount).map { slot ->
                val action = bindings.getOrElse(slot) { HomeAction.Probe(slot + 1) }
                val id = ID_BASE + (next.getAndIncrement() and ID_SPAN)
                Action(id, slot, action, "${label(action)}, ${directionWord(RadialGeometry.sectorAngle(slot, p))}")
            }
            return AccessibleDirections(list)
        }

        fun label(action: HomeAction) = if (action is HomeAction.Probe) "Unassigned direction ${action.number}" else action.label()
    }
}
