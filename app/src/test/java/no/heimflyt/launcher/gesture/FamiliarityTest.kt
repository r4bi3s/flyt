package no.heimflyt.launcher.gesture

import org.junit.Assert.*
import org.junit.Test

class FamiliarityTest {
    private val p=TuningParams()
    private fun clean(n:Int,from:SlotFamiliarity=SlotFamiliarity("k")) = (1..n).fold(from) { s,_ -> Familiarity.record(s,true,p) }

    @Test fun cleanDispatchesFadeGuidanceAtTrialThresholds() {
        assertEquals(Familiarity.FULL,clean(4).level)
        assertEquals(Familiarity.HINTED,clean(5).level)
        assertEquals(Familiarity.HINTED,clean(14).level)
        assertEquals(Familiarity.MINIMAL,clean(15).level)
        assertEquals(Familiarity.MINIMAL,clean(40).level)
    }
    @Test fun hesitationOrCancelStepsBackOneLevelAndResetsStreak() {
        val back=Familiarity.record(clean(15),false,p)
        assertEquals(SlotFamiliarity("k",Familiarity.HINTED,0),back)
        // Regaining minimal needs a fresh clean streak; a short streak never raises guidance.
        assertEquals(Familiarity.HINTED,clean(14,back).level)
        assertEquals(Familiarity.MINIMAL,clean(15,back).level)
        assertEquals(Familiarity.FULL,Familiarity.record(SlotFamiliarity("k"),false,p).level)
    }
    @Test fun hesitationEscalationIsTemporaryAndCapped() {
        assertEquals(Familiarity.MINIMAL,Familiarity.shown(Familiarity.MINIMAL,0))
        assertEquals(Familiarity.HINTED,Familiarity.shown(Familiarity.MINIMAL,1))
        assertEquals(Familiarity.FULL,Familiarity.shown(Familiarity.HINTED,2))
    }
    @Test fun directionKeyIgnoresPositionAndActivationButTracksDirection() {
        val base=TuningParams(arcSpan=360f,sectorCount=8)
        val same=base.copy(edgeDistance=200f,bottomDistance=300f,menuRadius=150f,deadZone=40f,anywhere=true,hysteresis=9f)
        repeat(8) { assertEquals(Familiarity.directionKey(it,base),Familiarity.directionKey(it,same)) }
        for(changed in listOf(base.copy(sectorCount=6),base.copy(arcSpan=180f),base.copy(arcStart=100f),base.copy(leftHanded=true)))
            assertNotEquals(Familiarity.directionKey(1,base),Familiarity.directionKey(1,changed))
        assertEquals("hidden",Familiarity.directionKey(7,base.copy(sectorCount=6)))
    }
    @Test fun reconcileResetsOnlyChangedSlots() {
        val learned=listOf(clean(20,SlotFamiliarity("a")),clean(20,SlotFamiliarity("b")))
        val r=Familiarity.reconcile(learned,listOf("a","c","d"))
        assertEquals(learned[0],r[0]); assertEquals(SlotFamiliarity("c"),r[1]); assertEquals(SlotFamiliarity("d"),r[2])
    }
}
