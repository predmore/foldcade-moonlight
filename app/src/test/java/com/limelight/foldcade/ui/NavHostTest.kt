package com.limelight.foldcade.ui

import com.limelight.foldcade.CompanionNav
import com.limelight.foldcade.CompanionNav.Direction.Down
import com.limelight.foldcade.CompanionNav.Direction.Left
import com.limelight.foldcade.CompanionNav.Direction.Right
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHostTest {
    private val nav = NavHost().apply { owned = true; layer = Layer.SETTINGS }

    private fun target(id: String, box: CompanionNav.Box, adjust: ((Int) -> Boolean)? = null,
                       moves: Map<CompanionNav.Direction, () -> String?> = emptyMap()) =
        NavTarget(id).also { it.box = box; it.layer = Layer.SETTINGS; it.adjust = adjust; it.moves = moves; nav.register(it) }

    @Test fun aRowAtTheEndOfItsValuesLetsLeftGoBackToTheCategories() {
        target("cat:video", CompanionNav.Box(0f, 0f, 150f, 50f))
        var stepped = 0
        target("set:fps", CompanionNav.Box(180f, 0f, 500f, 52f), adjust = { by -> if (by < 0) false else { stepped++; true } })
        nav.focus("set:fps")
        assertTrue(nav.move(Right))
        assertEquals(1, stepped)
        assertEquals("set:fps", nav.focused)
        // At the first value Left does not step: it moves on to the category.
        assertTrue(nav.move(Left))
        assertEquals("cat:video", nav.focused)
    }

    @Test fun rightFromACategoryLandsOnTheRowItNames() {
        target("set:resolution", CompanionNav.Box(180f, 0f, 500f, 52f))
        target("set:bitrate", CompanionNav.Box(180f, 52f, 500f, 120f))
        // Geometry alone would pick the bitrate row, beside this category; the category names its own.
        target("cat:video", CompanionNav.Box(0f, 70f, 150f, 120f), moves = mapOf(Right to { "set:resolution" }))
        nav.focus("cat:video")
        assertTrue(nav.move(Right))
        assertEquals("set:resolution", nav.focused)
    }

    @Test fun focusOnARowThatHasNotArrivedWaitsAndShowsNothing() {
        target("set:old", CompanionNav.Box(180f, 0f, 500f, 52f))
        nav.focus("set:old")
        nav.focus("set:speakers")
        assertEquals("set:speakers", nav.pending)
        assertFalse("no ring on the old row while the new page arrives", nav.isFocused("set:old"))
        assertFalse(nav.isFocused("set:speakers"))
        target("set:speakers", CompanionNav.Box(180f, 0f, 500f, 52f))
        assertNull(nav.pending)
        assertTrue(nav.isFocused("set:speakers"))
    }

    @Test fun aPendingFocusThatNeverArrivesGivesUpOnTheNextMove() {
        target("set:a", CompanionNav.Box(0f, 0f, 100f, 50f))
        target("set:b", CompanionNav.Box(0f, 60f, 100f, 110f))
        nav.focus("set:a")
        nav.focus("set:gone")
        nav.move(Down)
        assertNull(nav.pending)
        assertEquals("set:b", nav.focused)
    }

    @Test fun focusNeverShowsWhenTheGameHasTheController() {
        target("set:a", CompanionNav.Box(0f, 0f, 100f, 50f))
        nav.focus("set:a")
        nav.owned = false
        assertFalse(nav.isFocused("set:a"))
    }

    @Test fun leftFromAnyRowReturnsToItsOwnCategoryNotTheNearest() {
        val ui = CompanionUi("Game", "PC").apply { category = "audio" }
        // Audio's last row sits beside Controls; geometry alone would land there and swap the page.
        target("cat:video", CompanionNav.Box(0f, 0f, 150f, 50f))
        target("cat:audio", CompanionNav.Box(0f, 50f, 150f, 100f))
        target("cat:controls", CompanionNav.Box(0f, 100f, 150f, 150f))
        target("set:host", CompanionNav.Box(180f, 104f, 500f, 156f), moves = rowExits(ui, portrait = false, first = false))
        nav.focus("set:host")
        assertTrue(nav.move(Left))
        assertEquals("cat:audio", nav.focused)
        assertEquals("audio", ui.category)
    }

    @Test fun inPortraitUpLeavesOnlyFromTheFirstRow() {
        val ui = CompanionUi("Game", "PC").apply { category = "audio" }
        assertTrue(rowExits(ui, portrait = true, first = true).containsKey(CompanionNav.Direction.Up))
        assertTrue(rowExits(ui, portrait = true, first = false).isEmpty())
        assertEquals("cat:audio", rowExits(ui, portrait = true, first = true).getValue(CompanionNav.Direction.Up)())
    }
}
