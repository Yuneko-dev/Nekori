@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences

class TranslationSecretsBackupTest {
    private val store = InMemoryPreferenceStore()
    private val prefs = TranslationPreferences(store)
    private val creator = PreferenceBackupCreator(mockk(), store)
    private val legacyKeys = setOf(
        "translation_openai_api_key", "translation_nvidia_nim_base_url", "translation_nvidia_nim_api_key",
        "translation_deepseek_api_key", "translation_libretranslate_url", "translation_libretranslate_api_key",
        "translation_systran_api_key", "translation_deepl_api_key", "translation_google_api_key",
        "translation_ollama_url", "translation_custom_http_url", "translation_custom_http_api_key",
        "translation_custom_http_headers", "translation_custom_http_request_template", "translation_gemini_api_key",
    )
    private val providerJson = """ [ {"id":"test","endpoint":"https://test.invalid/?token=dummy","customHeaders":""" +
        """[{"name":"X-Api-Key","value":"dummy"}]} ] """

    @Test
    fun `ordinary backup excludes current and legacy credentials without altering saved data`() {
        legacyKeys.forEach { store.getString(it).set("dummy") }
        prefs.aiProvidersJson().set(providerJson)
        prefs.aiProviderApiKey("deleted-provider").set("dummy")
        prefs.libreTranslateApiKey().set("dummy")
        prefs.deepLApiKey().set("dummy")
        prefs.googleApiKey().set("dummy")
        prefs.targetLanguage().set("vi")

        val backup = creator.createApp(false).associateBy { it.key }

        assertFalse(backup.containsKey(prefs.aiProvidersJson().key()))
        assertTrue(legacyKeys.none(backup::containsKey))
        assertTrue(backup.keys.none { Preference.isPrivate(it) })
        assertEquals(StringPreferenceValue("vi"), backup[prefs.targetLanguage().key()]?.value)
        assertEquals(providerJson, prefs.aiProvidersJson().get())
        assertEquals("dummy", prefs.aiProviderApiKey("deleted-provider").get())
    }

    @Test
    fun `sensitive backup retains exact provider bytes and original secret keys`() {
        prefs.aiProvidersJson().set(providerJson)
        prefs.aiProviderApiKey("one").set("dummy")
        legacyKeys.forEach { store.getString(it).set("dummy-$it") }
        store.getString(Preference.appStateKey("test")).set("transient")
        val backup = creator.createApp(true).associateBy { it.key }

        assertEquals(StringPreferenceValue(providerJson), backup[prefs.aiProvidersJson().key()]?.value)
        assertEquals(StringPreferenceValue("dummy"), backup[prefs.aiProviderApiKey("one").key()]?.value)
        legacyKeys.forEach { assertEquals(StringPreferenceValue("dummy-$it"), backup[it]?.value) }
        assertTrue(backup.keys.none { Preference.isAppState(it) })
    }

    @Test
    fun `blank app restore preserves credentials but nonblank values replace them`() = runTest {
        val keys = legacyKeys + prefs.aiProvidersJson().key() + prefs.aiProviderApiKey("deleted-provider").key() +
            prefs.libreTranslateApiKey().key() + prefs.deepLApiKey().key() + prefs.googleApiKey().key()
        keys.forEach { store.getString(it).set("saved") }
        val restorer = PreferenceRestorer(mockk(), mockk(), store)
        assertTrue(restorer.restorePreferences(keys.map { value(it, " \t") }, store).isEmpty())
        keys.forEach { assertEquals("saved", store.getString(it).get(), it) }
        restorer.restorePreferences(keys.map { value(it, "replacement") }, store)
        keys.forEach { assertEquals("replacement", store.getString(it).get(), it) }
    }

    @Test
    fun `explicit empty provider array clears and missing preferences remain untouched`() = runTest {
        prefs.aiProvidersJson().set(providerJson)
        prefs.targetLanguage().set("vi")
        val restorer = PreferenceRestorer(mockk(), mockk(), store)
        restorer.restorePreferences(emptyList(), store)
        assertEquals(providerJson, prefs.aiProvidersJson().get())
        restorer.restorePreferences(
            listOf(value(prefs.aiProvidersJson().key(), "[]"), value(prefs.targetLanguage().key(), "")),
            store,
        )
        assertEquals("[]", prefs.aiProvidersJson().get())
        assertEquals("", prefs.targetLanguage().get())
    }

    private fun value(key: String, text: String) = BackupPreference(key, StringPreferenceValue(text))

    @Test
    fun `source preferences with translation-like names retain ordinary restore semantics`() = runTest {
        prefs.aiProvidersJson().set("saved")
        PreferenceRestorer(mockk(), mockk(), store).restorePreferences(
            listOf(value(prefs.aiProvidersJson().key(), "")),
            store,
            isAppPreferences = false,
        )
        assertEquals("", prefs.aiProvidersJson().get())
    }
}
