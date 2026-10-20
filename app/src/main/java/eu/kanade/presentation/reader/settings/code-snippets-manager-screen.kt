@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CodeSnippetsManagerScreen(private val isJavaScript: Boolean) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember { Injekt.get<ReaderPreferences>() }
        CodeSnippetsScreen(
            preference = if (isJavaScript) preferences.novelCustomJsSnippets else preferences.novelCustomCssSnippets,
            isJavaScript = isJavaScript,
            onBack = { navigator.pop() },
        )
    }
}
