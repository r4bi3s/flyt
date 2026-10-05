package no.heimflyt.launcher.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import no.heimflyt.launcher.HomeAction
import no.heimflyt.launcher.R
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space

@Composable
fun DirectionsSheet(
    bindings: List<HomeAction>, tuning: TuningParams, learning: Boolean,
    onDispatch: (HomeAction) -> Unit, onApps: () -> Unit, onSearch: () -> Unit, onTune: () -> Unit,
    onHideHints: () -> Unit, onDismiss: () -> Unit,
) {
    val t = Heimflyt.t; val c = t.color
    val p = tuning.safe()
    var howOpen by remember { mutableStateOf(false) }
    HSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Directions", style = t.type.title, modifier = Modifier.weight(1f))
                Text("${p.sectorCount} · ${if (p.arcSpan >= 360f) "full" else "half"} · ${if (p.anywhere) "anywhere" else "fixed"}", style = t.type.meta)
            }
            Spacer(Modifier.height(Space.s))
            for (slot in 0 until p.sectorCount) {
                val action = bindings.getOrElse(slot) { HomeAction.Probe(slot + 1) }
                val angle = RadialGeometry.sectorAngle(slot, p)
                val unassigned = action is HomeAction.Probe
                HRow(slotLabel(action), onClick = { onDispatch(action) },
                    leading = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DirectionArrow(angle, c.accent, 16.dp); Spacer(Modifier.width(2.dp))
                            Glyph(glyphFor(action), if (unassigned) c.inkMuted else c.ink, 18.dp)
                        }
                    },
                    trailing = { Text("${slot + 1}", style = t.type.meta) })
            }
            HorizontalDivider(Modifier.padding(vertical = Space.s), color = c.hairline)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FooterButton(R.drawable.glyph_apps, "Apps", Modifier.weight(1f), onApps)
                FooterButton(R.drawable.glyph_search, "Search", Modifier.weight(1f), onSearch)
                FooterButton(R.drawable.glyph_tune, "Tune", Modifier.weight(1f), onTune)
            }
            if (learning) {
                Row { QuietButton("How directions work") { howOpen = !howOpen }; QuietButton("Hide hints", onClick = onHideHints) }
                if (howOpen) Text("1  Touch empty Home space.\n2  Slide toward a direction.\n3  Release. Back to the centre cancels.",
                    style = t.type.secondary.copy(color = c.ink), modifier = Modifier.padding(Space.s))
            }
        }
    }
}

@Composable
private fun FooterButton(res: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    Row(modifier.heightIn(min = Space.target).clip(Shapes.s).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Glyph(res, c.ink, 18.dp); Spacer(Modifier.width(Space.s)); Text(label, style = t.type.label)
    }
}
