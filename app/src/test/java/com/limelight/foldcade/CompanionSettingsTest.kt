package com.limelight.foldcade

import com.limelight.foldcade.CompanionSettings.NATIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSettingsTest {
    private class MapStore : CompanionSettings.Store {
        val map = HashMap<String, Any>()
        override fun has(key: String) = map.containsKey(key)
        override fun string(key: String) = map[key] as? String
        override fun integer(key: String) = map[key] as? Int
        override fun bool(key: String) = map[key] as? Boolean
        override fun put(key: String, value: Any?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private val store = MapStore()
    private val s = CompanionSettings

    @Test fun resolutionIsTheTopScreenUntilThePlayerChooses() {
        assertEquals(NATIVE, s.resolutionChoice(store))
        // Moonlight's own first-run default is not a choice.
        store.put("list_resolution", "1280x720")
        assertEquals(NATIVE, s.resolutionChoice(store))
        // A choice made in standalone Moonlight is honoured.
        store.put("list_resolution", "2560x1440")
        assertEquals("2560x1440", s.resolutionChoice(store))
    }

    @Test fun anExplicit720pChoiceHereIsHonoured() {
        s.setChoice(store, s.resolution, "1280x720")
        assertEquals("1280x720", store.string("list_resolution"))
        assertEquals("1280x720", s.resolutionChoice(store))
        s.setChoice(store, s.resolution, NATIVE)
        assertEquals(NATIVE, s.resolutionChoice(store))
        // Choosing the top screen leaves Moonlight's own resolution as standalone set it.
        assertEquals("1280x720", store.string("list_resolution"))
    }

    @Test fun settingsUseMoonlightsKeysAndValues() {
        s.setChoice(store, s.codec, s.stepChoice(s.codec, s.choice(store, s.codec), 1))
        assertEquals("neverh265", store.string("video_format"))
        s.setToggle(store, s.hdr, true)
        assertEquals(true, store.bool("checkbox_enable_hdr"))
        s.setChoice(store, s.pacing, "smoothness")
        assertEquals("smoothness", store.string("frame_pacing"))
        assertEquals("2", s.choice(store, s.speakers))
        store.put("list_audio_config", "bogus")
        assertEquals("2", s.choice(store, s.speakers))
    }

    @Test fun bitrateStepsFromWhatAutoStandsFor() {
        assertNull(s.steps(store, s.bitrate))
        // Auto at 20 Mbps: Right is 25 and Left is 15, never a jump back to the slider's start.
        assertEquals(true to 25000, s.stepSteps(s.bitrate, null, 1, 20000))
        assertEquals(true to 15000, s.stepSteps(s.bitrate, null, -1, 20000))
        // An Auto value between stops steps to the stop on that side.
        assertEquals(true to 30000, s.stepSteps(s.bitrate, null, 1, 28000))
        assertEquals(true to 25000, s.stepSteps(s.bitrate, null, -1, 28000))
        s.setSteps(store, s.bitrate, 25000)
        assertEquals(25000, store.integer("seekbar_bitrate_kbps"))
        assertEquals(true to 30000, s.stepSteps(s.bitrate, 25000, 1, 20000))
        // The ends stop instead of wrapping, so Left can leave the row.
        assertFalse(s.stepSteps(s.bitrate, 5000, -1).first)
        assertFalse(s.stepSteps(s.bitrate, 150000, 1).first)
        s.setSteps(store, s.bitrate, null)
        assertFalse(store.has("seekbar_bitrate_kbps"))
        // A value Moonlight's own slider wrote sits on the nearest stop.
        store.put("seekbar_bitrate_kbps", 33500)
        assertEquals(35000, s.steps(store, s.bitrate))
        assertEquals("20 Mbps · Auto", s.stepsLabel(s.bitrate, null, 20000))
        assertEquals("25 Mbps", s.stepsLabel(s.bitrate, 25000, 20000))
        assertEquals("7%", s.stepsLabel(s.deadzone, 7))
    }

    @Test fun theThumbSitsWhereTheValueIsEvenOnAuto() {
        val auto = s.fraction(s.bitrate, null, 20000)
        assertEquals(s.fraction(s.bitrate, 20000), auto, 0.0001f)
        assertTrue(auto > 0.1f)
        assertEquals(0f, s.fraction(s.bitrate, 5000), 0f)
        assertEquals(1f, s.fraction(s.bitrate, 150000), 0f)
    }

    @Test fun choicesStopAtTheirEndsForLeftAndRightButAWraps() {
        assertNull(s.stepChoiceBounded(s.fps, "30", -1))
        assertEquals("60", s.stepChoiceBounded(s.fps, "30", 1))
        assertNull(s.stepChoiceBounded(s.fps, "120", 1))
        assertFalse(s.canStep(s.resolution, NATIVE, -1))
        assertTrue(s.canStep(s.resolution, NATIVE, 1))
        assertEquals("30", s.stepChoice(s.fps, "120", 1))
    }

    @Test fun addedSettingsUseMoonlightsKeys() {
        val keys = s.categories.flatMap { it.settings }.map { it.key }.toSet()
        for (key in listOf("checkbox_stretch_video", "checkbox_full_range", "checkbox_enable_perf_overlay",
                "checkbox_gamepad_motion_fallback", "checkbox_vibrate_fallback", "checkbox_absolute_mouse_mode")) {
            assertTrue(key, key in keys)
        }
        // A toggle Moonlight never reads in a stream is not offered.
        assertFalse("checkbox_unlock_fps" in keys)
    }

    @Test fun pendingListsOnlyStreamSettingsChangedSinceTheStreamStarted() {
        val applied = s.restartSnapshot(store)
        assertTrue(s.pending(applied, s.restartSnapshot(store)).isEmpty())
        s.setSteps(store, s.padSpeed, 150)
        s.setToggle(store, s.naturalScroll, false)
        assertTrue("trackpad settings apply live", s.pending(applied, s.restartSnapshot(store)).isEmpty())
        s.setChoice(store, s.fps, "120")
        s.setChoice(store, s.resolution, "1920x1080")
        assertEquals(listOf("Resolution", "Frame rate"), s.pending(applied, s.restartSnapshot(store)))
        s.setChoice(store, s.fps, "60")
        s.setChoice(store, s.resolution, NATIVE)
        assertTrue(s.pending(applied, s.restartSnapshot(store)).isEmpty())
        assertTrue(s.pending(null, s.restartSnapshot(store)).isEmpty())
    }

    @Test fun choicesWrap() {
        assertEquals("120", s.stepChoice(s.fps, "30", -1))
        assertEquals("30", s.stepChoice(s.fps, "120", 1))
        assertEquals("Top screen", s.optionLabel(s.resolution, NATIVE))
    }
}
