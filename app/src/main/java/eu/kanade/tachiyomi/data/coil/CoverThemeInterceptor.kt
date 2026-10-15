package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import coil3.asDrawable
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.materialkolor.ktx.themeColorOrNull
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

/** Warm the seed while Library/Browse loads the cover, before the details screen is opened. */
class CoverThemeInterceptor : Interceptor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val cover = when (val data = chain.request.data) {
            is Manga -> data.asMangaCover()
            is MangaCover -> data
            else -> return chain.proceed()
        }
        if (cover.hasUnavailableCover() ||
            !Injekt.get<UiPreferences>().themeCoverBased.get() || CoverThemeCache.contains(cover)
        ) {
            return chain.proceed()
        }
        val request = chain.request.newBuilder().allowHardware(false).build()
        val result = chain.withRequest(request).proceed()
        if (result is SuccessResult && CoverThemeCache.begin(cover)) {
            scope.launch {
                try {
                    val bitmap = result.image.asDrawable(request.context.resources).getBitmapOrNull()
                        ?: return@launch
                    val scale = minOf(1f, 128f / maxOf(bitmap.width, bitmap.height))
                    val sample = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                    try {
                        CoverThemeCache.put(cover, sample.asImageBitmap().themeColorOrNull()?.toArgb())
                    } finally {
                        if (sample !== bitmap) sample.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Optional theme metadata must never turn a successful cover load into an error.
                } finally {
                    CoverThemeCache.finish(cover)
                }
            }
        }
        return result
    }
}
