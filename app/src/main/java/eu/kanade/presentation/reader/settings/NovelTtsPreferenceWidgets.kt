package eu.kanade.presentation.reader.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.setting.NovelTtsEngine
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.tts.tiktok.TikTokVoiceCatalog
import tachiyomi.tts.tiktok.displayName
import java.util.Locale

@Composable
fun NovelTtsEnginePreference(preferences: ReaderPreferences) {
    val context = LocalContext.current
    val storedValue by preferences.novelTtsEngine.collectAsState()
    val selected = NovelTtsEngine.fromPreference(storedValue)
    val installedEngines = remember(context) { installedAndroidTtsEngines(context.packageManager) }
    val entries = linkedMapOf(
        NovelTtsEngine.SystemDefault.preferenceValue to stringResource(TDMR.strings.novel_tts_system_engine),
    ).apply {
        installedEngines.forEach { (packageName, label) ->
            put(NovelTtsEngine.Android(packageName).preferenceValue, label)
        }
        if (selected is NovelTtsEngine.Android && selected.preferenceValue !in this) {
            put(selected.preferenceValue, selected.packageName)
        }
        put(
            NovelTtsEngine.TikTok.preferenceValue,
            stringResource(TDMR.strings.novel_tts_tiktok_engine),
        )
    }

    ListPreferenceWidget(
        value = selected.preferenceValue,
        title = stringResource(TDMR.strings.pref_novel_tts_engine),
        subtitle = entries[selected.preferenceValue],
        icon = null,
        entries = entries,
        onValueChange = { value ->
            val next = NovelTtsEngine.fromPreference(value)
            if (selected != next && selected !is NovelTtsEngine.TikTok && next !is NovelTtsEngine.TikTok) {
                preferences.novelTtsVoice.set("")
            }
            preferences.novelTtsEngine.set(next.preferenceValue)
        },
    )
}

@Composable
fun NovelTtsVoicePreference(preferences: ReaderPreferences) {
    val context = LocalContext.current
    val storedEngine by preferences.novelTtsEngine.collectAsState()
    val androidVoice by preferences.novelTtsVoice.collectAsState()
    val tikTokVoice by preferences.novelTtsTikTokVoice.collectAsState()
    val engine = NovelTtsEngine.fromPreference(storedEngine)
    val defaultVoiceLabel = stringResource(TDMR.strings.novel_tts_default_voice)
    var entries by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    DisposableEffect(engine.preferenceValue) {
        var probe: TextToSpeech? = null
        var disposed = false

        if (engine is NovelTtsEngine.TikTok) {
            entries = TikTokVoiceCatalog.voices.associate { it.id to it.displayName() }
            if (TikTokVoiceCatalog.find(tikTokVoice) == null) {
                preferences.novelTtsTikTokVoice.set(TikTokVoiceCatalog.defaultFor(Locale.getDefault()).id)
            }
        } else {
            entries = mapOf("" to defaultVoiceLabel)
            val listener = TextToSpeech.OnInitListener { status ->
                if (!disposed && status == TextToSpeech.SUCCESS) {
                    val voices = probe?.voices.orEmpty()
                        .sortedWith(compareBy({ it.locale.displayLanguage.lowercase() }, { it.name }))
                    entries = linkedMapOf("" to defaultVoiceLabel).apply {
                        voices.forEach { voice ->
                            put(voice.name, "${voice.locale.displayLanguage} (${voice.name})")
                        }
                    }
                    if (androidVoice.isNotEmpty() && androidVoice !in entries) {
                        preferences.novelTtsVoice.set("")
                    }
                }
            }
            probe = when (engine) {
                is NovelTtsEngine.Android -> TextToSpeech(context.applicationContext, listener, engine.packageName)
                else -> TextToSpeech(context.applicationContext, listener)
            }
        }

        onDispose {
            disposed = true
            probe?.shutdown()
        }
    }

    val selectedVoice = if (engine is NovelTtsEngine.TikTok) tikTokVoice else androidVoice
    ListPreferenceWidget(
        value = selectedVoice,
        title = stringResource(TDMR.strings.pref_novel_tts_voice),
        subtitle = entries[selectedVoice] ?: defaultVoiceLabel,
        icon = null,
        entries = entries,
        onValueChange = { value ->
            if (engine is NovelTtsEngine.TikTok) {
                preferences.novelTtsTikTokVoice.set(value)
            } else {
                preferences.novelTtsVoice.set(value)
            }
        },
    )
}

@Suppress("DEPRECATION")
private fun installedAndroidTtsEngines(packageManager: PackageManager): List<Pair<String, String>> {
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    return packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        .map { info -> info.serviceInfo.packageName to info.serviceInfo.loadLabel(packageManager).toString() }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
