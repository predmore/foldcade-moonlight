package com.limelight.foldcade.ui

/**
 * What the deck's header gives up when it runs short, in order. The header holds the Moonlight
 * mark, a slot (the game's title over the PC's name, or the detail metrics), the stats pill, and
 * the device status (battery and clock). Each list below is a ladder: the header takes the first
 * step that fits, so information never goes before decoration, and the battery glyph and a low
 * percent never go at all.
 */
internal object HeaderFit {
    /** Widths in pixels, measured on the real text at the current font scale. */
    data class Widths(
        val available: Float,
        /** The mark and the gap after it. */
        val mark: Float,
        /** The gap between the slot and the pill. */
        val pillGap: Float,
        val pill: Float,
        /** The gap before the status. */
        val statusGap: Float,
        /** The status with its clock, the clock at its reserved two-digit-hour width. */
        val status: Float,
        /** The status without its clock: the glyph, and the percent when low. */
        val statusNoClock: Float,
        /** Room the title wants before the mark gives way: 88dp at the font scale, or less for a short title. */
        val titleWant: Float,
        /** The least the title shows: its first five characters and an ellipsis, or all of a shorter title. */
        val titleMin: Float,
        /** Each detail metric's width, in order, and the gap between them. */
        val metrics: List<Float> = emptyList(),
        val metricGap: Float = 0f,
    )

    /** One step: what the header shows. */
    data class Plan(
        val mark: Boolean = true,
        val title: Boolean = true,
        /** Detail metrics shown, from the left. */
        val metrics: Int = Int.MAX_VALUE,
        val clock: Boolean = true,
        /** No title and no room for the mark: the pill starts at the edge, with no gap before it. */
        val pillAtEdge: Boolean = false,
    )

    /** With the title: the mark goes; then the title (the mark returns if it fits); then the clock. */
    val titleSteps = listOf(
        Plan(),
        Plan(mark = false),
        Plan(title = false),
        Plan(mark = false, title = false, pillAtEdge = true),
        Plan(mark = false, title = false, pillAtEdge = true, clock = false),
    )

    /** The first title step without the title: coming back from here waits longer, as the title is the largest thing. */
    const val TITLE_GONE = 2

    /** With detail: the mark, resolution/codec, the clock, then metrics from the right. */
    val detailSteps = listOf(
        Plan(metrics = 3),
        Plan(mark = false, metrics = 3),
        Plan(mark = false, metrics = 2),
        Plan(mark = false, metrics = 2, clock = false),
        Plan(mark = false, metrics = 1, clock = false),
        Plan(mark = false, metrics = 0, clock = false),
    )

    /** The index of the first step in [steps] that fits [w]; the last step otherwise. */
    fun step(w: Widths, detail: Boolean): Int {
        val steps = if (detail) detailSteps else titleSteps
        val first = steps.indexOfFirst { fits(w, it, detail) }
        return if (first < 0) steps.lastIndex else first
    }

    private fun fits(w: Widths, plan: Plan, detail: Boolean): Boolean {
        val rest = (if (plan.mark) w.mark else 0f) + (if (plan.pillAtEdge) 0f else w.pillGap) + w.pill +
            w.statusGap + (if (plan.clock) w.status else w.statusNoClock)
        val slot = w.available - rest
        return when {
            detail -> slot >= metricsWidth(w, plan.metrics)
            !plan.title -> slot >= 0f
            plan.mark -> slot >= w.titleWant
            else -> slot >= w.titleMin
        }
    }

    private fun metricsWidth(w: Widths, count: Int): Float {
        val shown = w.metrics.take(count)
        return if (shown.isEmpty()) 0f else shown.sum() + w.metricGap * (shown.size - 1)
    }
}
