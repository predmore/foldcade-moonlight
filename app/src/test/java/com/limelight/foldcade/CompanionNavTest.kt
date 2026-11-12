package com.limelight.foldcade

import com.limelight.foldcade.CompanionNav.Box
import com.limelight.foldcade.CompanionNav.Direction.Down
import com.limelight.foldcade.CompanionNav.Direction.Left
import com.limelight.foldcade.CompanionNav.Direction.Right
import com.limelight.foldcade.CompanionNav.Direction.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionNavTest {
    // The deck: six keys over the trackpad, a rail of four tiles to the right, the stats pill above.
    private val keys = (0 until 6).associate { "key$it" to Box(10f + it * 70f, 60f, 70f + it * 70f, 120f) }
    private val rail = (0 until 4).associate { "tile$it" to Box(440f, 60f + it * 90f, 520f, 140f + it * 90f) }
    private val stats = mapOf("stats" to Box(400f, 5f, 520f, 45f))
    private val all = keys + rail + stats

    @Test fun keysStepAlongTheirRowAndIntoTheRail() {
        assertEquals("key1", CompanionNav.next(keys.getValue("key0"), all, Right))
        assertEquals("tile0", CompanionNav.next(keys.getValue("key5"), all, Right))
        assertEquals("key5", CompanionNav.next(rail.getValue("tile0"), all, Left))
        assertNull(CompanionNav.next(keys.getValue("key0"), all, Left))
    }

    @Test fun railStepsDownAndUpToTheStats() {
        assertEquals("tile1", CompanionNav.next(rail.getValue("tile0"), all, Down))
        assertEquals("tile2", CompanionNav.next(rail.getValue("tile1"), all, Down))
        assertNull(CompanionNav.next(rail.getValue("tile3"), all, Down))
        assertEquals("stats", CompanionNav.next(rail.getValue("tile0"), all, Up))
    }

    @Test fun aControlInLineWinsOverANearerOneToTheSide() {
        val boxes = mapOf(
            "near-diagonal" to Box(110f, 60f, 150f, 90f),
            "far-in-line" to Box(300f, 0f, 340f, 40f),
        )
        assertEquals("far-in-line", CompanionNav.next(Box(0f, 0f, 100f, 40f), boxes, Right))
    }

    @Test fun settingsRowsScrolledOutOfViewStillFollowInOrder() {
        val rows = (0 until 8).associate { "row$it" to Box(180f, 10f + it * 60f, 520f, 62f + it * 60f) }
        val rail = (0 until 4).associate { "cat$it" to Box(10f, 60f + it * 52f, 160f, 110f + it * 52f) }
        assertEquals("row7", CompanionNav.next(rows.getValue("row6"), rows + rail, Down))
        assertEquals("cat3", CompanionNav.next(rows.getValue("row3"), rows + rail, Left))
        assertEquals("row1", CompanionNav.next(rail.getValue("cat0"), rows + rail, Right))
    }
}
