package com.limelight.foldcade

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Android-independent: where the D-pad goes next among the companion's controls, by their
 * places on the screen. A control in line with the current one (sharing some of its height for
 * left and right, or its width for up and down) wins over a nearer one off to the side.
 */
object CompanionNav {
    enum class Direction { Up, Down, Left, Right }

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f
    }

    fun next(from: Box, candidates: Map<String, Box>, direction: Direction): String? {
        var best: String? = null
        var bestInLine = false
        var bestScore = Float.MAX_VALUE
        for ((id, box) in candidates) {
            if (box == from) continue
            val gap = when (direction) {
                Direction.Right -> box.left - from.right
                Direction.Left -> from.left - box.right
                Direction.Down -> box.top - from.bottom
                Direction.Up -> from.top - box.bottom
            }
            val ahead = when (direction) {
                Direction.Right -> box.centerX > from.centerX
                Direction.Left -> box.centerX < from.centerX
                Direction.Down -> box.centerY > from.centerY
                Direction.Up -> box.centerY < from.centerY
            }
            // Ahead, and not mostly overlapping it the way a control inside another would.
            if (!ahead || gap < -minSpan(from, box, direction) * 0.5f) continue
            val horizontal = direction == Direction.Left || direction == Direction.Right
            val overlap = if (horizontal) min(from.bottom, box.bottom) - max(from.top, box.top)
            else min(from.right, box.right) - max(from.left, box.left)
            val inLine = overlap > 0f
            val offAxis = if (horizontal) abs(box.centerY - from.centerY) else abs(box.centerX - from.centerX)
            val score = max(gap, 0f) + offAxis * (if (inLine) 0.5f else 2f)
            if ((inLine && !bestInLine) || (inLine == bestInLine && score < bestScore)) {
                best = id
                bestInLine = inLine
                bestScore = score
            }
        }
        return best
    }

    private fun minSpan(a: Box, b: Box, direction: Direction): Float =
        if (direction == Direction.Left || direction == Direction.Right) min(a.right - a.left, b.right - b.left)
        else min(a.bottom - a.top, b.bottom - b.top)
}
