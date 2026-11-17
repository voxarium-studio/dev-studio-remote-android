package com.voxarium.remote

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResourcesSmokeTest {
    @Test fun identityLabelsDoNotConflateSaveVerifySelectCreateAndConnect() {
        val app = RuntimeEnvironment.getApplication()
        val actions = listOf(R.string.profile_save, R.string.server_verify, R.string.project_select,
            R.string.project_create, R.string.connect)
        for (language in listOf("en-US", "ru-RU")) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            })
            assertEquals(5, actions.map { context.getString(it) }.toSet().size)
            assertTrue(context.getString(R.string.project_id, "exact-id").contains("exact-id"))
            assertTrue(context.getString(R.string.recovery_server, "https://exact.test/prefix").contains("https://exact.test/prefix"))
            assertTrue(context.getString(R.string.recovery_generation, 42L).contains("42"))
        }
    }

    @Test
    fun everyUiMessageResolvesInEnglishAndRussianWithoutFallback() {
        val app = RuntimeEnvironment.getApplication()
        fun localized(language: String) = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            },
        )
        val english = localized("en-US")
        val russian = localized("ru-RU")
        val messageIds = R.string::class.java.fields.filter { it.name != "app_name" }.map { it.getInt(null) }
        for (id in messageIds) {
            val name = app.resources.getResourceEntryName(id)
            val en = english.getString(id)
            val ru = russian.getString(id)
            assertFalse("Empty English message: $name", en.isBlank())
            assertFalse("Empty Russian message: $name", ru.isBlank())
            assertNotEquals("Russian message fell back to English: $name", en, ru)
            assertTrue("Russian message lacks Cyrillic text: $name", ru.any { it in '\u0400'..'\u04FF' })
            assertEquals("Format placeholders differ: $name",
                Regex("%[0-9]+\\$[sd]").findAll(en).map { it.value }.toList(),
                Regex("%[0-9]+\\$[sd]").findAll(ru).map { it.value }.toList())
        }
        // The product name is a brand, deliberately identical in both locales.
        assertEquals("Voxarium Remote", english.getString(R.string.app_name))
        assertEquals("Voxarium Remote", russian.getString(R.string.app_name))
    }
}
