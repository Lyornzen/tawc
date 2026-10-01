package me.phie.tawc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GUI-scale preset list must stay consistent with the slider's grid
 * and with the compositor's clamp range — an off-grid entry would show
 * one value while a different one is stored, and an out-of-range entry
 * would be clamped behind the user's back (the Rust side clamps 0.5-4.0
 * independently).
 */
class SettingsScalePresetTest {

    @Test
    fun presetsAreOnTheSliderGrid() {
        for (preset in Settings.OUTPUT_SCALE_PRESETS) {
            assertEquals(
                "preset $preset is off the ${Settings.OUTPUT_SCALE_STEP} grid",
                preset,
                Settings.snapOutputScale(preset),
                0.0f,
            )
        }
    }

    @Test
    fun presetsAreInRange() {
        for (preset in Settings.OUTPUT_SCALE_PRESETS) {
            assertTrue(
                "preset $preset below ${Settings.MIN_OUTPUT_SCALE}",
                preset >= Settings.MIN_OUTPUT_SCALE,
            )
            assertTrue(
                "preset $preset above ${Settings.MAX_OUTPUT_SCALE}",
                preset <= Settings.MAX_OUTPUT_SCALE,
            )
        }
    }

    @Test
    fun presetsAreAscendingAndDistinct() {
        val presets = Settings.OUTPUT_SCALE_PRESETS
        assertEquals(presets.sorted(), presets)
        assertEquals(presets.size, presets.toSet().size)
    }

    @Test
    fun defaultScaleIsOffered() {
        // The dialog marks the current value; the default must always be
        // representable or the checkmark would silently fall back to the
        // first entry.
        assertTrue(Settings.DEFAULT_OUTPUT_SCALE in Settings.OUTPUT_SCALE_PRESETS)
    }

    @Test
    fun formattingMatchesTheSliderTitle() {
        // The dialog reuses the slider's formatter, so entries read the
        // same as the title they change.
        assertEquals("2.00", Settings.formatOutputScale(2.0f))
        assertEquals("1.25", Settings.formatOutputScale(1.25f))
    }
}
