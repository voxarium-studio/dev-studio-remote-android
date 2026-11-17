package com.voxarium.remote

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU-w320dp-h480dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmallScreenSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    // Configure font scaling before the activity is created, not after its first composition.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.setFontScale(2.0f)
        }

        override fun after() {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }).around(compose)

    @Test
    fun russianContentRemainsReachableOnSmallScreenWithLargeFont() {
        val configuration = compose.activity.resources.configuration
        assertEquals(320, configuration.screenWidthDp)
        assertEquals(2.0f, configuration.fontScale, 0.01f)

        compose.onNodeWithText("Нет подключения").performScrollTo().assertIsDisplayed()
        val footer = compose.onNodeWithText(compose.activity.getString(R.string.privacy_note))
        footer.performScrollTo().assertIsDisplayed()

        val layouts = mutableListOf<TextLayoutResult>()
        footer.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts))
        }
        assertTrue("Expected a measured footer, not just an existing semantics node", layouts.isNotEmpty())
        layouts.forEach { assertFalse("Footer must wrap without clipping", it.hasVisualOverflow) }

        // Scrolling back up must also work; the footer must not trap the viewport.
        compose.onNodeWithText("Voxarium Remote").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun russianProfileFormActionsRemainReachableAtDoubleFont() {
        // Native metadata initialization is asynchronous; a disabled button must not be clicked.
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Добавить сервер").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Добавить сервер").performScrollTo().performClick()
        compose.onNodeWithText("Название сервера").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Личный ключ доступа").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить сервер").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        compose.onNodeWithText("Серверы").performScrollTo().assertIsDisplayed()
    }
}

/** New identity screens keep actions reachable without horizontal clipping at fontScale=2. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU-w320dp-h480dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectsSmallScreenSmokeTest {
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(2.0f) }
        override fun after() { RuntimeEnvironment.setFontScale(1.0f) }
    }).around(compose)

    private fun projectScreen() {
        val server = com.voxarium.remote.data.security.ServerProfile.create("Сервер", "https://example.test/proxy", "credential")
        compose.setContent { com.voxarium.remote.ui.theme.VoxariumRemoteTheme {
            com.voxarium.remote.ui.connection.ProjectsScreen(RemoteUiState(selectedServer = server,
                verificationStatus = VerificationStatus.VERIFIED, catalogStatus = CatalogStatus.READY,
                verifiedAccount = com.voxarium.remote.data.transport.RemoteUser("account", "Аккаунт", "#112233")),
                {}, {}, {}, {}, { _, _ -> }, {}, {}, {})
        } }
    }

    @Test fun createFormAndFooterRemainReachableAtDoubleFont() {
        projectScreen()
        compose.onNodeWithText("Создать проект").performScrollTo().performClick()
        compose.onNodeWithText("Отображаемое название проекта").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ID проекта (точный)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверить перед созданием").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        compose.onNodeWithText("Локальное восстановление").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проекты").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "ru-rRU-w480dp-h320dp-night-mdpi")
    fun landscapeProjectActionsRemainReachable() {
        projectScreen()
        compose.onNodeWithText("Обновить проекты").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Создать проект").performScrollTo().performClick()
        compose.onNodeWithText("Проверить перед созданием").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        compose.onNodeWithText("Серверы").performScrollTo().assertIsDisplayed()
    }
}
