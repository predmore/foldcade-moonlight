package com.limelight.foldcade.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Widths in dp at density 1, from the design's mock of the Thor's bottom screen. */
class HeaderFitTest {
    private fun widths(
        available: Float = 440f, pill: Float = 120f, status: Float = 60f, statusNoClock: Float = 18f,
        titleWant: Float = 88f, titleMin: Float = 60f,
        metrics: List<Float> = listOf(52f, 36f, 75f),
    ) = HeaderFit.Widths(
        available = available, mark = 38f, pillGap = 10f, pill = pill, statusGap = 14f,
        status = status, statusNoClock = statusNoClock, titleWant = titleWant, titleMin = titleMin,
        metrics = metrics, metricGap = 18f,
    )

    @Test fun aRoomyHeaderGivesUpNothing() {
        assertEquals(0, HeaderFit.step(widths(), detail = false))
    }

    @Test fun theMarkGoesBeforeTheTitleIsCut() {
        // 379 - (38 + 10 + 165 + 14 + 92) = 60: short of 88 with the mark, 98 without.
        assertEquals(1, HeaderFit.step(widths(available = 379f, pill = 165f, status = 92f), detail = false))
    }

    @Test fun aShortTitleKeepsTheMarkWhenItFitsWhole() {
        assertEquals(0, HeaderFit.step(widths(available = 300f, titleWant = 45f, titleMin = 45f), detail = false))
    }

    @Test fun aTitleWhoseFirstFiveCharactersAreWideStillDropsTheMarkFirst() {
        // 90 for the title with the mark: past 88, but short of the 94 its first five characters need.
        assertEquals(1, HeaderFit.step(widths(available = 302f, titleMin = 94f), detail = false))
    }

    @Test fun belowItsFirstFiveCharactersTheTitleGivesWayAndTheMarkReturns() {
        // Without the mark the title would get 58, short of its 70; with the title gone the mark fits.
        assertEquals(HeaderFit.TITLE_GONE, HeaderFit.step(widths(available = 339f, pill = 165f, status = 92f, titleMin = 70f), detail = false))
    }

    @Test fun withNoRoomForTheMarkThePillStartsAtTheEdge() {
        val step = HeaderFit.step(widths(available = 300f, pill = 180f, status = 92f, statusNoClock = 50f), detail = false)
        assertEquals(3, step)
        assertEquals(true, HeaderFit.titleSteps[step].pillAtEdge)
    }

    @Test fun theClockGoesLastAndTheGlyphAndPercentStay() {
        val step = HeaderFit.step(widths(available = 300f, pill = 230f, status = 110f, statusNoClock = 50f), detail = false)
        assertEquals(4, step)
        assertEquals(false, HeaderFit.titleSteps[step].clock)
    }

    @Test fun detailDropsTheMarkThenResolutionThenTheClockThenMetricsFromTheRight() {
        // Metrics in full: 52 + 36 + 75 + 2 × 18 = 199.
        assertEquals(0, HeaderFit.step(widths(available = 460f), detail = true))
        assertEquals(1, HeaderFit.step(widths(available = 420f), detail = true))
        assertEquals(2, HeaderFit.step(widths(available = 379f), detail = true))
        // 2 metrics (106) + pill 120 + gaps 24, plus status 60 is 310; without the clock, 268.
        assertEquals(3, HeaderFit.step(widths(available = 280f), detail = true))
        assertEquals(4, HeaderFit.step(widths(available = 220f), detail = true))
        assertEquals(5, HeaderFit.step(widths(available = 150f), detail = true))
    }
}
