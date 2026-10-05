package no.heimflyt.launcher.ui.browse

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import no.heimflyt.launcher.ui.theme.FallbackTokens
import no.heimflyt.launcher.ui.theme.HeimflytTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * H6.1 review finding: holes in a small tag's grid must keep their row's geometry, so the destinations that remain are
 * drawn exactly where they were (rendered bounds, not just model indices). The grid is bottom-anchored as in Browse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StableGridGeometryTest {
    private fun render(states: List<List<String?>>, leftHanded: Boolean = false): List<Map<String, Rect>> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var index by mutableStateOf(0)
        val bounds = HashMap<String, Rect>()
        activity.setContent {
            HeimflytTheme(FallbackTokens.resolved) {
                Box(Modifier.size(400.dp, 800.dp)) {
                    // A fresh grid per state: positioning callbacks fire even when nothing moved (which is the point).
                    key(index) {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.weight(1f))
                            StableTagGrid(states[index], leftHanded) { label, m ->
                                DestinationTile(label, { Box(Modifier.size(48.dp)) }, m.onGloballyPositioned { bounds[label] = it.boundsInRoot() })
                            }
                        }
                    }
                }
            }
        }
        return states.indices.map { i ->
            index = i; bounds.clear()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            HashMap(bounds)
        }
    }

    // A long name wraps to two lines: the case where a hole shorter than a tile would collapse the row the most.
    private val long = "A destination with a long name"

    @Test fun bottomRowHolesKeepTheTopRowInPlace() {
        // Slots 0/1 are the bottom row, 2/3 the top row (BrowseModel.cells).
        val (full, holes) = render(listOf(listOf("Wallet", long, "Claude", "Grok"), listOf(null, null, "Claude", "Grok")))
        assertEquals(4, full.size); assertEquals(2, holes.size)
        assertEquals(full["Claude"], holes["Claude"])
        assertEquals(full["Grok"], holes["Grok"])
        assertTrue(full["Claude"]!!.bottom <= full["Wallet"]!!.top) // really the upper row
    }

    @Test fun oneHoleAndLeftHandKeepPositions() {
        val (full, hole) = render(listOf(listOf("Wallet", long, "Claude", "Grok"), listOf("Wallet", null, "Claude", "Grok")), leftHanded = true)
        listOf("Wallet", "Claude", "Grok").forEach { assertEquals(it, full[it], hole[it]) }
    }

    @Test fun topRowRemovedLeavesBottomRowUntouched() {
        // Trailing holes are dropped by the model (stableCells), so the grid shrinks from the top; the bottom row stays.
        val top = BrowseModel.stableCells(listOf("Wallet", "Vipps", "Claude", "Grok"), listOf("Vipps", "Wallet"))
        assertEquals(listOf("Wallet", "Vipps"), top)
        val (full, shrunk) = render(listOf(listOf("Wallet", "Vipps", "Claude", "Grok"), top))
        assertEquals(full["Wallet"], shrunk["Wallet"]); assertEquals(full["Vipps"], shrunk["Vipps"])
    }
}
