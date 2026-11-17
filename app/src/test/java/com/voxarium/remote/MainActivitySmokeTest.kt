package com.voxarium.remote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Launches the real manifest activity and reads its actual Compose semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun englishActivityShowsLocalProfilesWithoutFakeDocuments() {
        assertEquals("Documents", compose.activity.getString(R.string.documents_title))
        assertEquals("Disconnected", compose.activity.getString(R.string.status_disconnected))
        compose.onNodeWithText("Voxarium Remote").assertIsDisplayed()
        compose.onNodeWithText("Servers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Disconnected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add server").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.privacy_note)).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp-night-mdpi")
    fun russianNightActivityUsesRussianResources() {
        assertEquals("Документы", compose.activity.getString(R.string.documents_title))
        assertEquals("Нет подключения", compose.activity.getString(R.string.status_disconnected))
        compose.onNodeWithText("Voxarium Remote").assertIsDisplayed()
        compose.onNodeWithText("Серверы").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Нет подключения").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Добавить сервер").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.privacy_note)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun passwordIsClearedAcrossRotationAndBack() {
        fun find(view: View): EditText? = when (view) {
            is EditText -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Add server").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Add server").performScrollTo().performClick()
        compose.runOnIdle { find(compose.activity.window.decorView)!!.setText("rotation-test-only") }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        lateinit var password: EditText
        compose.runOnIdle {
            password = find(compose.activity.window.decorView)!!
            assertEquals("", password.text.toString())
            password.setText("back-test-only")
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("Servers").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("", password.text.toString()) }
    }
}
