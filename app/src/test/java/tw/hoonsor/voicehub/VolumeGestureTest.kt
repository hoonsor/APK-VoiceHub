package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.hoonsor.voicehub.voice.VolumeGestureDetector
import tw.hoonsor.voicehub.voice.VolumePattern

/** 音量鍵手勢的判斷邏輯 */
class VolumeGestureTest {
    @Test fun upDownUpMatchesAndReturnsOriginalVolume() {
        val d = VolumeGestureDetector(VolumePattern.UP_DOWN_UP)
        assertNull(d.feed(5, 6, 0))
        assertNull(d.feed(6, 5, 300))
        assertEquals(5, d.feed(5, 6, 600))
    }

    @Test fun fourthPressDoesNotRetrigger() {
        val d = VolumeGestureDetector(VolumePattern.UP3)
        d.feed(1, 2, 0); d.feed(2, 3, 200)
        assertEquals(1, d.feed(3, 4, 400))
        assertNull(d.feed(4, 5, 600))
    }

    @Test fun tooSlowBetweenPressesResets() {
        val d = VolumeGestureDetector(VolumePattern.UP_DOWN_UP)
        d.feed(5, 6, 0)
        d.feed(6, 5, 1500) // 間隔超過 900ms，重新算
        assertNull(d.feed(5, 6, 1800))
    }

    @Test fun wholeGestureMustFitInTotalWindow() {
        val d = VolumeGestureDetector(VolumePattern.UP3, gapMs = 900, totalMs = 2000)
        d.feed(1, 2, 0); d.feed(2, 3, 850); assertNull(d.feed(3, 4, 2100))
    }

    @Test fun normalVolumeAdjustDoesNotMatchUpDownUp() {
        val d = VolumeGestureDetector(VolumePattern.UP_DOWN_UP)
        listOf(3 to 4, 4 to 5, 5 to 6, 6 to 7).forEachIndexed { i, (a, b) -> assertNull(d.feed(a, b, i * 200L)) }
    }

    @Test fun longerSequenceEndingInPatternMatches() {
        val d = VolumeGestureDetector(VolumePattern.UP_DOWN_UP)
        d.feed(4, 3, 0)
        d.feed(3, 4, 200); d.feed(4, 3, 400)
        assertEquals(3, d.feed(3, 4, 600))
    }

    @Test fun unchangedVolumeIgnoredAndUnknownPatternFallsBack() {
        val d = VolumeGestureDetector(VolumePattern.DOWN3)
        assertNull(d.feed(5, 5, 0))
        assertEquals(VolumePattern.UP_DOWN_UP, VolumePattern.of("???"))
        assertEquals(VolumePattern.DOWN3, VolumePattern.of("DOWN3"))
    }
}
