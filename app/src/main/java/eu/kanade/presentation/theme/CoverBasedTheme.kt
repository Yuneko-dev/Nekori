package eu.kanade.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.DynamicScheme
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.toColorScheme
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.coil.CoverThemeCache
import eu.kanade.tachiyomi.data.coil.hasUnavailableCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Shares the cover request and palette policy between details and native reader controls. */
@Composable
fun CoverBasedTheme(manga: Manga?, content: @Composable () -> Unit) {
    val preferences = remember { Injekt.get<UiPreferences>() }
    val enabled by preferences.themeCoverBased.collectAsState()
    val style by preferences.themeCoverBasedStyle.collectAsState()
    val amoled by preferences.themeDarkAmoled.collectAsState()
    val context = LocalContext.current
    val cover = manga?.asMangaCover()?.takeUnless { it.hasUnavailableCover() }
    var seed by remember(cover, enabled) {
        mutableStateOf(cover?.takeIf { enabled }?.let { CoverThemeCache[it]?.let(::Color) })
    }

    LaunchedEffect(cover, enabled, context) {
        if (!enabled || cover == null) return@LaunchedEffect
        launch {
            CoverThemeCache.observe(cover).collect { seed = it?.let(::Color) }
        }
        if (CoverThemeCache.contains(cover)) return@LaunchedEffect
        withIOContext {
            try {
                val request = ImageRequest.Builder(context)
                    .data(cover)
                    .allowHardware(false) // Color extraction must read the bitmap's pixels.
                    .size(256)
                    .build()
                context.imageLoader.execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // A missing or unreadable cover keeps the surrounding app theme.
            }
        }
    }

    val color = seed
    val dark = isSystemInDarkTheme()
    val fallback = MaterialTheme.colorScheme
    val scheme = remember(color, style, dark, amoled, fallback) {
        color?.let { coverColorScheme(it, style, dark, amoled) } ?: fallback
    }
    val animatedScheme = animateColorScheme(scheme)
    MaterialExpressiveTheme(colorScheme = animatedScheme) {
        CompositionLocalProvider(LocalContentColor provides animatedScheme.onBackground, content = content)
    }
}

internal fun coverColorScheme(seed: Color, style: PaletteStyle, dark: Boolean, amoled: Boolean) =
    DynamicScheme(seedColor = seed, isDark = dark, style = style).toColorScheme(isAmoled = amoled)
