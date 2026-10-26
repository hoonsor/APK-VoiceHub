package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.hoonsor.voicehub.voice.VolumeGestureDetector
import tw.hoonsor.voicehub.voice.VolumePattern
import tw.hoonsor.voicehub.voice.VolumeSpeed

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

    @Test fun missedFirstPressIsReconstructed() {
        // 實機紀錄：加（101→111）被合併掉，只收到 減、加
        val d = VolumeGestureDetector(VolumePattern.UP_DOWN_UP, VolumeSpeed.SLOW)
        d.sync(101)
        assertNull(d.feedObserved(111, 101, 1000))
        assertEquals(101, d.feedObserved(101, 111, 2200))
    }

    @Test fun slowSpeedToleratesDelayedDelivery() {
        val slow = VolumeGestureDetector(VolumePattern.UP_DOWN_UP, VolumeSpeed.SLOW)
        slow.sync(60)
        slow.feedObserved(60, 70, 0); slow.feedObserved(70, 60, 1800)
        assertEquals(60, slow.feedObserved(60, 70, 4000))
        val fast = VolumeGestureDetector(VolumePattern.UP_DOWN_UP, VolumeSpeed.FAST)
        fast.sync(60)
        fast.feedObserved(60, 70, 0); fast.feedObserved(70, 60, 1800)
        assertNull(fast.feedObserved(60, 70, 4000))
    }

    @Test fun syncAfterRestorePreventsFakeStep() {
        val d = VolumeGestureDetector(VolumePattern.UP3, VolumeSpeed.SLOW)
        d.sync(5)
        d.feedObserved(5, 6, 0); d.feedObserved(6, 7, 300)
        assertEquals(5, d.feedObserved(7, 8, 600))
        d.sync(5) // APP 把音量還原成 5
        assertNull(d.feedObserved(5, 6, 3000))
        assertNull(d.feedObserved(6, 7, 3300))
    }
}
