package eu.kanade.presentation.more.settings.screen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The AI sparkle used by LNReader's settings screen. */
internal val AiIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AI",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 64f,
        viewportHeight = 64f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(AI_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private const val AI_PATH =
    "M32 0C32.6711 0 33.2553.458263 33.4189 1.10938C33.9209 3.10093 34.5758 5.04389 35.3906 " +
        "6.93359C37.5131 11.8639 40.4247 16.1796 44.1221 19.877C47.8215 23.5745 52.1357 26.4869 " +
        "57.0664 28.6094C58.958 29.4242 60.899 30.0791 62.8906 30.5811C63.5415 30.7448 63.9998 " +
        "31.3281 64 31.999C64 32.6701 63.5417 33.2542 62.8906 33.418C60.899 33.9199 58.9561 " +
        "34.5748 57.0664 35.3896C52.1358 37.5121 47.8196 40.4237 44.1221 44.1211C40.4246 " +
        "47.8204 37.5131 52.1349 35.3906 57.0654C34.5758 58.957 33.9209 60.8981 33.4189 " +
        "62.8896C33.2552 63.5407 32.6711 63.999 32 63.999C31.3289 63.999 30.7448 63.5407 30.5811 " +
        "62.8896C30.0791 60.8981 29.4242 58.9551 28.6094 57.0654C26.4869 52.1349 23.5773 " +
        "47.8186 19.8779 44.1211C16.1786 40.4237 11.8642 37.5121 6.93359 35.3896C5.04204 " +
        "34.5748 3.10096 33.9199 1.10938 33.418C.458309 33.2542 0 32.6701 0 31.999C.000201548 " +
        "31.3281.458463 30.7448 1.10938 30.5811C3.10096 30.0791 5.04386 29.4242 6.93359 " +
        "28.6094C11.8643 26.4869 16.1804 23.5745 19.8779 19.877C23.5753 16.1796 26.4869 " +
        "11.8639 28.6094 6.93359C29.4242 5.04207 30.0791 3.10093 30.5811 1.10938C30.7448.45826 " +
        "31.3289 0 32 0Z"
