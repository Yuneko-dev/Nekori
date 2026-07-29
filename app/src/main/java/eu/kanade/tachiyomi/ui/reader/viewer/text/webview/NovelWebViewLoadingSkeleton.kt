package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import kotlin.math.roundToInt

// WebView port of LNReader's SkeletonLines and react-native-shimmer-placeholder 2.0.9.
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
        val lineGap = cssNumber(style.fontSize * (style.lineHeight - 1.0))
        val lineHeight = cssNumber(style.fontSize * style.lineHeight.toDouble())

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * { box-sizing: border-box; }
                    html, body { height: 100%; overflow: hidden; }
                    body {
                        margin: 0;
                        padding: ${style.marginTop}px ${style.marginRight}px ${style.marginBottom}px ${style.marginLeft}px;
                        background-color: ${ThemeUtils.colorToHex(style.backgroundColor)};
                    }
                    .skeleton {
                        display: flex;
                        flex-direction: column;
                        height: 100%;
                        overflow: hidden;
                        position: relative;
                        width: 100%;
                    }
                    .line {
                        flex-shrink: 0;
                        height: ${style.fontSize}px;
                        margin: 0 0 ${lineGap}px;
                        border-radius: 8px;
                        background: $baseColor;
                        overflow: hidden;
                        position: relative;
                    }
                    .shimmer {
                        position: absolute;
                        inset: 0;
                        background: linear-gradient(
                            90deg,
                            $baseColor -10%,
                            $highlightColor 50%,
                            $baseColor 110%
                        );
                        transform: translateX(-100%);
                        animation: shimmer 1000ms infinite;
                        will-change: transform;
                    }
                    .gap {
                        flex-shrink: 0;
                        height: ${lineGap}px;
                        margin: 8px;
                    }
                    .status {
                        position: absolute;
                        width: 1px;
                        height: 1px;
                        padding: 0;
                        margin: -1px;
                        overflow: hidden;
                        clip: rect(0, 0, 0, 0);
                        white-space: nowrap;
                        border: 0;
                    }
                    @keyframes shimmer {
                        0% {
                            transform: translateX(-100%);
                            animation-timing-function: cubic-bezier(0.42, 0, 1, 1);
                        }
                        50% {
                            transform: translateX(0);
                            animation-timing-function: cubic-bezier(0, 0, 0.58, 1);
                        }
                        100% { transform: translateX(100%); }
                    }
                    @media (prefers-reduced-motion: reduce) {
                        .shimmer { animation: none; }
                    }
                </style>
            </head>
            <body>
                <div class="status" role="status">${HtmlUtils.escapeHtml(message)}</div>
                <main id="skeleton" class="skeleton" aria-hidden="true"></main>
                <script>
                    (() => {
                        const root = document.getElementById('skeleton');
                        const lineHeight = $lineHeight;
                        let availableHeight = window.innerHeight - 10;
                        let consecutiveLines = 0;
                        const items = [];

                        while (availableHeight > lineHeight) {
                            if (Math.random() * 4 > 1 && consecutiveLines <= 5) {
                                items.push(true);
                                availableHeight -= lineHeight;
                                consecutiveLines++;
                            } else {
                                items.push(false);
                                availableHeight -= 16;
                                consecutiveLines = 0;
                            }
                        }

                        items.forEach((isLine, index) => {
                            const element = document.createElement('div');
                            const endsParagraph = items[index + 1] !== undefined && !items[index + 1];
                            if (endsParagraph || isLine) {
                                const width = endsParagraph ? Math.max(0.1, Math.random()) * 90 : 90;
                                element.className = 'line';
                                element.style.width = `${'$'}{width}vw`;
                                const shimmer = document.createElement('div');
                                shimmer.className = 'shimmer';
                                element.appendChild(shimmer);
                            } else {
                                element.className = 'gap';
                            }
                            root.appendChild(element);
                        });
                    })();
                </script>
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
