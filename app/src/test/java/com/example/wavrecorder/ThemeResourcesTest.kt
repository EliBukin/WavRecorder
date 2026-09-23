package com.example.wavrecorder

import android.app.Application
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Clean" Day/Night theme: every role resolves in both light and dark, text pairings keep WCAG
 * AA contrast, the system bars follow the theme, and nothing visible still depends on the old
 * purple/teal starter palette.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeResourcesTest {

    private fun themed(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_WavRecorder)

    private fun Context.attrColor(@AttrRes attr: Int): Int {
        val value = TypedValue()
        check(theme.resolveAttribute(attr, value, true)) { "attribute not set in the theme" }
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun Context.attrBoolean(@AttrRes attr: Int): Boolean {
        val value = TypedValue()
        check(theme.resolveAttribute(attr, value, true))
        return value.data != 0
    }

    private fun contrast(a: Int, b: Int) = ColorUtils.calculateContrast(a, b)

    /** The pairings every screen relies on, each required to meet WCAG AA for text (4.5:1). */
    private fun assertReadable(context: Context) {
        val background = context.attrColor(android.R.attr.colorBackground)
        val surface = context.attrColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = context.attrColor(com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = context.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = context.attrColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = context.attrColor(com.google.android.material.R.attr.colorOnPrimary)
        val error = context.attrColor(com.google.android.material.R.attr.colorError)
        val onError = context.attrColor(com.google.android.material.R.attr.colorOnError)
        val primaryContainer = context.attrColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = context.attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val secondaryContainer = context.attrColor(com.google.android.material.R.attr.colorSecondaryContainer)
        val onSecondaryContainer = context.attrColor(com.google.android.material.R.attr.colorOnSecondaryContainer)

        val pairs = mapOf(
            "primary text on background" to contrast(onSurface, background),
            "primary text on surface" to contrast(onSurface, surface),
            "secondary text on surface" to contrast(onSurfaceVariant, surface),
            "secondary text on background" to contrast(onSurfaceVariant, background),
            "accent text on surface" to contrast(primary, surface),
            "Start recording label" to contrast(onPrimary, primary),
            "Stop & save label" to contrast(onError, error),
            "delete/error text on surface" to contrast(error, surface),
            "selected row text" to contrast(onSurface, primaryContainer),
            "on-primary-container text" to contrast(onPrimaryContainer, primaryContainer),
            "selection toolbar text" to contrast(onSecondaryContainer, secondaryContainer)
        )
        pairs.forEach { (name, ratio) -> assertTrue("$name contrast is only $ratio", ratio >= 4.5) }

        // Status dots are non-text UI: WCAG asks for 3:1 against their surroundings.
        listOf(R.color.status_verified_green, R.color.status_detected_blue, R.color.status_warning_orange,
            R.color.status_recording_red).forEach { res ->
            val ratio = contrast(ContextCompat.getColor(context, res), surface)
            assertTrue("status color ${context.resources.getResourceEntryName(res)} contrast is only $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun `light theme colors are readable`() = assertReadable(themed())

    @Test
    @Config(qualifiers = "night")
    fun `dark theme colors are readable`() = assertReadable(themed())

    @Test
    fun `light theme uses a light background with dark system-bar icons`() {
        val context = themed()
        val background = context.attrColor(android.R.attr.colorBackground)
        assertTrue(ColorUtils.calculateLuminance(background) > 0.8)
        assertTrue(context.attrBoolean(android.R.attr.windowLightStatusBar))
        assertEquals("the status bar blends into the header", background, context.attrColor(android.R.attr.statusBarColor))
    }

    @Test
    @Config(qualifiers = "night")
    fun `dark theme uses a dark background with light system-bar icons`() {
        val context = themed()
        val background = context.attrColor(android.R.attr.colorBackground)
        assertTrue(ColorUtils.calculateLuminance(background) < 0.05)
        assertFalse(context.attrBoolean(android.R.attr.windowLightStatusBar))
        assertEquals(background, context.attrColor(android.R.attr.statusBarColor))
    }

    @Test
    @Config(sdk = [30])
    fun `the navigation bar matches the bottom navigation in light mode (A20, Android 11)`() {
        val context = themed()
        assertEquals(
            context.attrColor(com.google.android.material.R.attr.colorSurfaceContainer),
            context.attrColor(android.R.attr.navigationBarColor)
        )
        assertTrue(context.attrBoolean(android.R.attr.windowLightNavigationBar))
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun `the navigation bar matches the bottom navigation in dark mode (A20, Android 11)`() {
        val context = themed()
        assertEquals(
            context.attrColor(com.google.android.material.R.attr.colorSurfaceContainer),
            context.attrColor(android.R.attr.navigationBarColor)
        )
        assertFalse(context.attrBoolean(android.R.attr.windowLightNavigationBar))
    }

    @Test
    fun `light surfaces are light`() {
        assertTrue(ColorUtils.calculateLuminance(themed().attrColor(com.google.android.material.R.attr.colorSurface)) > 0.9)
    }

    @Test
    @Config(qualifiers = "night")
    fun `dark surfaces are dark`() {
        assertTrue(ColorUtils.calculateLuminance(themed().attrColor(com.google.android.material.R.attr.colorSurface)) < 0.1)
    }

    @Test
    fun `the old purple and teal starter palette is gone`() {
        val context = themed()
        val res = context.resources
        listOf("purple_500", "purple_700", "teal_200", "text_secondary", "status_preferred_purple").forEach {
            assertEquals("$it should no longer exist", 0, res.getIdentifier(it, "color", context.packageName))
        }
        val oldPurple = 0xFF6200EE.toInt()
        listOf(com.google.android.material.R.attr.colorPrimary, com.google.android.material.R.attr.colorSecondary,
            com.google.android.material.R.attr.colorTertiary).forEach {
            assertNotEquals(oldPurple, context.attrColor(it))
        }
    }

    @Test
    fun `the accent is a calm blue and red is reserved for recording and errors`() {
        val context = themed()
        val primary = context.attrColor(com.google.android.material.R.attr.colorPrimary)
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(primary, it) }
        assertTrue("accent hue ${hsl[0]} should be blue", hsl[0] in 195f..225f)
        val error = context.attrColor(com.google.android.material.R.attr.colorError)
        val errorHsl = FloatArray(3).also { ColorUtils.colorToHSL(error, it) }
        assertTrue("error/record hue ${errorHsl[0]} should be red", errorHsl[0] < 15f || errorHsl[0] > 345f)
        assertEquals(error, ContextCompat.getColor(context, R.color.status_recording_red))
    }
}
