package no.heimflyt.launcher.ui.home

import org.junit.Assert.*
import org.junit.Test

class ArcLabelsTest {
    @Test fun upperHalfReadsClockwiseLowerHalfIsReversedToStayUpright() {
        // Screen angles: 0 = right, 90 = down, 270 = up.
        for (a in listOf(270f, 247.5f, 292.5f, 202.5f, 337.5f, 0f, 180f)) assertTrue("$a", ArcLabels.clockwise(a))
        for (a in listOf(90f, 22.5f, 67.5f, 112.5f, 157.5f)) assertFalse("$a", ArcLabels.clockwise(a))
    }
}
