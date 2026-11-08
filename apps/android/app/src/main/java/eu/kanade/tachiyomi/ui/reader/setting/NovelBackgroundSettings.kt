package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NovelBackgroundSettings(
    val image: String = "",
    val size: String = "cover",
    val position: String = "center",
    val repeat: Boolean = false,
    val opacity: Int = 100,
    val blur: Int = 0,
) {
    fun sanitized() = copy(
        size = size.takeIf { it in SIZES } ?: "cover",
        position = position.takeIf { it in POSITIONS } ?: "center",
        opacity = opacity.coerceIn(0, 100),
        blur = blur.coerceIn(0, 20),
    )

    val mode: String
        get() = when {
            repeat -> "tile"
            size == "contain" -> "fit"
            size == "stretch" -> "stretch"
            size == "auto" -> "center"
            else -> "fill"
        }

    fun withMode(mode: String) = copy(
        size = when (mode) {
            "fit" -> "contain"
            "stretch" -> "stretch"
            "tile", "center" -> "auto"
            else -> "cover"
        },
        repeat = mode == "tile",
        position = if (mode == "tile") "top left" else "center",
    )

    companion object {
        val SIZES = listOf("cover", "contain", "stretch", "auto")
        val POSITIONS = listOf("center", "top", "bottom", "left", "right", "top left")

        fun decode(value: String): NovelBackgroundSettings = runCatching {
            Json.decodeFromString<NovelBackgroundSettings>(value).sanitized()
        }.getOrDefault(NovelBackgroundSettings())
    }
}

fun ReaderPreferences.backgroundSettings() = NovelBackgroundSettings.decode(novelBackground.get())

fun ReaderPreferences.setBackgroundSettings(settings: NovelBackgroundSettings) {
    novelBackground.set(Json.encodeToString(settings.sanitized()))
}
