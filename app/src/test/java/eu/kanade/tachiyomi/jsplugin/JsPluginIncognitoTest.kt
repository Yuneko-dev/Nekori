package eu.kanade.tachiyomi.jsplugin

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.discord.SensitiveContentPolicy
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class JsPluginIncognitoTest {
    private val preferenceStore = InMemoryPreferenceStore()
    private val basePreferences = BasePreferences(mockk<Context>(relaxed = true), preferenceStore)
    private val sourcePreferences = SourcePreferences(preferenceStore)
    private val securityPreferences = SecurityPreferences(preferenceStore)
    private val plugin = JsPlugin(
        id = "example",
        name = "Example",
        site = "https://example.com",
        lang = "English",
        version = "1.0.0",
    )
    private val installedPluginState = MutableStateFlow(
        listOf(
            InstalledJsPlugin(
                plugin = plugin,
                code = "",
                installedVersion = plugin.version,
                repositoryUrl = "https://example.com/plugins.json",
            ),
        ),
    )
    private val pluginManager = mockk<JsPluginManager> {
        every { installedPlugins } returns installedPluginState
        every { contentWarningForSource(any()) } returns null
    }
    private val getIncognitoState = GetIncognitoState(basePreferences, sourcePreferences, pluginManager)

    @Test
    fun `plugin incognito applies to its source`() = runTest {
        sourcePreferences.incognitoExtensions.set(setOf(plugin.pkgName()))

        getIncognitoState.await(plugin.sourceId()) shouldBe true
        getIncognitoState.subscribe(plugin.sourceId()).first() shouldBe true
    }

    @Test
    fun `plugin incognito does not apply to another source`() {
        sourcePreferences.incognitoExtensions.set(setOf(plugin.pkgName()))

        getIncognitoState.await(plugin.sourceId() + 1) shouldBe false
    }

    @Test
    fun `plugin incognito blocks reader and Discord actions`() {
        sourcePreferences.incognitoExtensions.set(setOf(plugin.pkgName()))
        val policy = SensitiveContentPolicy(getIncognitoState, securityPreferences, pluginManager)

        SensitiveContentPolicy.Action.entries.forEach { action ->
            policy.isBlocked(action, plugin.sourceId()) shouldBe true
        }
    }
}
