package com.limelight.foldcade

import com.limelight.foldcade.DeviceStatus.Battery
import com.limelight.foldcade.DeviceStatus.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusTest {
    @Test fun theBatteryStepsByTensAsFoldcadeBucketsIt() {
        assertEquals(0, Rules.step(4))
        assertEquals(10, Rules.step(5))
        assertEquals(10, Rules.step(14))
        assertEquals(20, Rules.step(15))
        assertEquals(100, Rules.step(95))
        assertEquals(100, Rules.step(140))
        assertEquals(0, Rules.step(-3))
    }

    @Test fun theFillFollowsFoldcadesArtWithASliverAtTenPercent() {
        assertEquals(0f, Rules.fillUnits(0), 0f)
        assertEquals(1.6f, Rules.fillUnits(10), 0.001f)
        assertEquals(2.64f, Rules.fillUnits(20), 0.001f)
        assertEquals(13.2f, Rules.fillUnits(100), 0.001f)
    }

    @Test fun thePercentJoinsAtTwentyAndTurnsRoseWhereTheGlyphDoes() {
        assertFalse(Battery(21, false).low)
        assertTrue(Battery(20, false).low)
        assertFalse(Battery(15, false).critical)
        assertTrue(Battery(14, false).critical)
        // The glyph's fill turns rose exactly where the percent does: Foldcade's 10% step and below.
        assertEquals(10, Battery(14, false).step)
        assertEquals(20, Battery(15, false).step)
    }

    @Test fun chargingIsNeverLow() {
        assertFalse(Battery(5, true).low)
        assertFalse(Battery(5, true).critical)
    }

    @Test fun theClockDropsItsDayPeriodWhereverTheLocalePutsIt() {
        assertEquals("h:mm", Rules.withoutDayPeriod("h:mm a"))
        assertEquals("h:mm", Rules.withoutDayPeriod("h:mm a"))
        assertEquals("h:mm", Rules.withoutDayPeriod("a h:mm"))
        assertEquals("h:mm", Rules.withoutDayPeriod("ah:mm"))
        assertEquals("h:mm", Rules.withoutDayPeriod("h:mm B"))
        assertEquals("HH:mm", Rules.withoutDayPeriod("HH:mm"))
        assertEquals("HH 'h' mm", Rules.withoutDayPeriod("HH 'h' mm"))
        assertEquals("h 'a' mm", Rules.withoutDayPeriod("h 'a' mm a"))
    }

    @Test fun roseReadsOnADarkThemeButNotOnALightOne() {
        val rose = 0xFFFF5C7A.toInt()
        assertEquals(21.0, Rules.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertTrue(Rules.contrast(rose, 0xFF000000.toInt()) >= 3.0)
        assertTrue(Rules.contrast(rose, 0xFFF3EFE6.toInt()) < 3.0)
    }

    @Test fun theSpokenStatusKeepsTheFullTime() {
        assertEquals("12:41 PM", Rules.spoken("12:41 PM", null))
        assertEquals("12:41 PM, battery 82 percent", Rules.spoken("12:41 PM", Battery(82, false)))
        assertEquals("12:41 PM, battery 82 percent, charging", Rules.spoken("12:41 PM", Battery(82, true)))
        assertEquals("12:41 PM, battery low, 12 percent", Rules.spoken("12:41 PM", Battery(12, false)))
    }
}
