package com.limelight.foldcade

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class StreamHealthTest {
    private fun stats(fps: Float = 60f, rtt: Int = 4, decode: Float = 2f, loss: Float = 0f) =
        StreamStats(fps, rtt, decode, loss, 1920, 1080, 0)

    @Test fun aStillDesktopAtLowFrameRateIsHealthy() {
        val slow = stats(fps = 21f)
        assertEquals(0, StreamHealth.verdict(slow, 60).level)
        assertEquals("21 fps · 4 ms", StreamHealth.pillText(slow, 60, Locale.US))
    }

    @Test fun thePillNamesWhatIsWrong() {
        assertEquals("21 fps · 6% loss", StreamHealth.pillText(stats(fps = 21f, loss = 6f), 60, Locale.US))
        assertEquals(2, StreamHealth.verdict(stats(loss = 6f), 60).level)
        assertEquals("60 fps · 12 ms decode", StreamHealth.pillText(stats(decode = 12f), 60, Locale.US))
        assertEquals(1, StreamHealth.verdict(stats(decode = 12f), 60).level)
        assertEquals("60 fps · 48 ms ping", StreamHealth.pillText(stats(rtt = 48), 60, Locale.US))
    }

    @Test fun theWorstProblemNamesItselfAndLossLeadsATie() {
        assertEquals(StreamHealth.Cause.Decode, StreamHealth.verdict(stats(loss = 2f, decode = 20f), 60).cause)
        assertEquals(StreamHealth.Cause.Loss, StreamHealth.verdict(stats(loss = 2f, rtt = 40), 60).cause)
        // At 120 fps the frame budget is half, so the same decode time is a problem sooner.
        assertEquals(0, StreamHealth.verdict(stats(decode = 5f), 60).level)
        assertEquals(1, StreamHealth.verdict(stats(decode = 5f), 120).level)
    }
}
