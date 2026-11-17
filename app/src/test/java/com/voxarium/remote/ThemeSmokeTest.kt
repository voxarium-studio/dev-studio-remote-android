package com.voxarium.remote

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.voxarium.remote.ui.theme.VoxariumRemoteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-night")
    fun explicitLightThemeOverridesNightConfiguration() {
        assertLight(readComposedScheme(darkTheme = false))
    }

    @Test
    fun explicitDarkThemeOverridesDayConfiguration() {
        assertDark(readComposedScheme(darkTheme = true))
    }

    @Test
    fun defaultThemeFollowsSystemDayConfiguration() {
        assertLight(readComposedScheme())
    }

    @Test
    @Config(qualifiers = "en-rUS-night")
    fun defaultThemeFollowsSystemNightConfiguration() {
        assertDark(readComposedScheme())
    }

    /** Capture MaterialTheme *inside* the production theme, after composition commits. */
    private fun readComposedScheme(darkTheme: Boolean? = null): ColorScheme {
        lateinit var actual: ColorScheme
        compose.setContent {
            val probe: @androidx.compose.runtime.Composable () -> Unit = {
                val colors = MaterialTheme.colorScheme
                SideEffect { actual = colors }
                Text("Theme probe")
            }
            if (darkTheme == null) {
                VoxariumRemoteTheme(content = probe)
            } else {
                VoxariumRemoteTheme(darkTheme = darkTheme, content = probe)
            }
        }
        return compose.runOnIdle { actual }
    }

    private fun assertLight(colors: ColorScheme) {
        assertEquals(Color(0xFF365C45), colors.primary)
        assertEquals(Color.White, colors.onPrimary)
        assertEquals(Color(0xFFF7F9F5), colors.background)
        assertEquals(Color(0xFFF7F9F5), colors.surface)
        assertEquals(Color(0xFF1A211C), colors.onSurface)
    }

    private fun assertDark(colors: ColorScheme) {
        assertEquals(Color(0xFFABD2B4), colors.primary)
        assertEquals(Color(0xFF143822), colors.onPrimary)
        assertEquals(Color(0xFF111713), colors.background)
        assertEquals(Color(0xFF111713), colors.surface)
        assertEquals(Color(0xFFE0E7DE), colors.onSurface)
    }
}
