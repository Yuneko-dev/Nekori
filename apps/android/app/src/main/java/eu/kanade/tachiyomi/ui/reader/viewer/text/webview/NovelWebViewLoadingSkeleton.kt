package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal object NovelWebViewLoadingSkeleton {
    data class Style(
        val backgroundColor: Int,
        val fontSize: Int,
        val lineHeight: Float,
        val marginLeft: Int,
        val marginRight: Int,
        val marginTop: Int,
        val marginBottom: Int,
    )

    fun buildHtml(style: Style, message: String): String {
        val (baseColor, highlightColor) = shimmerColors(style.backgroundColor)
        val config = buildJsonObject {
            put("background", ThemeUtils.colorToHex(style.backgroundColor))
            put("base", baseColor)
            put("highlight", highlightColor)
            put("fontSize", style.fontSize)
            put("lineGap", cssNumber(style.fontSize * (style.lineHeight - 1.0)).toDouble())
            put("lineHeight", cssNumber(style.fontSize * style.lineHeight.toDouble()).toDouble())
            put("marginLeft", style.marginLeft)
            put("marginRight", style.marginRight)
            put("marginTop", style.marginTop)
            put("marginBottom", style.marginBottom)
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="stylesheet" href="https://nekori.reader/assets/skeleton.css">
            </head>
            <body>
                <div class="status" role="status">${HtmlUtils.escapeHtml(message)}</div>
                <main id="skeleton" class="skeleton" aria-hidden="true"></main>
                <script id="nekori-skeleton-config" type="application/json">$config</script>
                <script src="https://nekori.reader/assets/skeleton.js"></script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun shimmerColors(background: Int): Pair<String, String> {
        if (background and RGB_MASK == 0) {
            val negated = background xor RGB_MASK
            return adjustLightness(negated, -0.98) to adjustLightness(negated, -0.92)
        }

        return if (isDark(background)) {
            adjustLightness(background, 0.10) to adjustLightness(background, 0.40)
        } else {
            adjustLightness(background, -0.04) to adjustLightness(background, -0.08)
        }
    }

    private fun isDark(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return (red * 2126 + green * 7152 + blue * 722) / 10_000.0 < 128
    }

    private fun adjustLightness(color: Int, ratio: Double): String {
        val red = ((color shr 16) and 0xFF) / 255.0
        val green = ((color shr 8) and 0xFF) / 255.0
        val blue = (color and 0xFF) / 255.0
        val min = minOf(red, green, blue)
        val max = maxOf(red, green, blue)
        val delta = max - min
        val lightness = (min + max) / 2

        val hue = when {
            delta == 0.0 -> 0.0
            max == red -> (green - blue) / delta
            max == green -> 2 + (blue - red) / delta
            else -> 4 + (red - green) / delta
        }.let { minOf(it * 60, 360.0) }
            .let { if (it < 0) it + 360 else it }

        val saturation = when {
            delta == 0.0 -> 0.0
            lightness <= 0.5 -> delta / (max + min)
            else -> delta / (2 - max - min)
        }
        val adjustedLightness = (lightness + lightness * ratio).coerceIn(0.0, 1.0)

        return "hsl(${cssNumber(hue)}, ${cssNumber(saturation * 100)}%, ${cssNumber(adjustedLightness * 100)}%)"
    }

    private fun cssNumber(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    private const val RGB_MASK = 0x00FFFFFF
}
