package tachiyomi.tts.tiktok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object TikTokProtocol {
    data class Event(val name: String, val error: String? = null)

    fun startMessage(text: String, voice: String): String {
        val payload = buildJsonObject {
            put(
                "audio_config",
                buildJsonObject {
                    put("bit_rate", 128_000)
                    put("format", "pcm")
                    put("sample_rate", SAMPLE_RATE)
                },
            )
            put("speaker", voice)
            put("text", text.trim())
        }
        return buildJsonObject {
            put("appkey", APP_KEY)
            put("event", "StartTask")
            put("namespace", "TTS")
            put("payload", payload.toString())
            put("token", TOKEN)
            put("version", "sdk_v1")
        }.toString()
    }

    fun event(message: String): Event? = runCatching {
        val json = Json.parseToJsonElement(message).jsonObject
        val name = json["event"]?.jsonPrimitive?.content ?: return null
        val error = if (name == "TaskFailed") {
            val code = json["status_code"]?.jsonPrimitive?.intOrNull ?: -1
            val text = json["status_text"]?.jsonPrimitive?.content ?: "Unknown"
            "TaskFailed: $code - $text"
        } else {
            null
        }
        Event(name, error)
    }.getOrNull()

    const val SAMPLE_RATE = 24_000

    const val ENDPOINT =
        "wss://sami-normal-sg.capcutapi.com/internal/api/v1/ws?" +
            "device_id=7486429558272460289&iid=7486431924195657473&app_id=359289&region=VN&" +
            "update_version_code=5.7.1.2101&version_code=5.7.1&appKey=ddjeqjLGMn&" +
            "device_type=macos&device_platform=macos"

    private const val APP_KEY = "ddjeqjLGMn"
    private const val TOKEN =
        "WTV6R2t6V3ZwNUIwQkFETutGxuveRZ9iTmOBC/a3wzMS7zzza86Ky9nIfYhyeoSiWYP1ZO04X7X1+" +
            "RThg/zczU6u8ga3dTIJpduvWpCqrmr0Kv7BJf6tcGFgevJ/Jaa1slHj/l4NUJ/eCesl1dYBYQ51oKbuFnZjF7" +
            "qXVWzsoz326XwRdNEmOufSHnuW+kuy+sS7K/sn3gVWsCC4XFi+FYntDxrVTYS/Pv2LtBgpgULmib5+5kMq2" +
            "ZuJfCDYvq4NthciciB6KUCf1sOsu7VD/27Tquz8Q58NYALFvX85bjvxQJOz0iV3oUiip0RyqR1ltZPNI/LgN" +
            "2OGCphyCgOJdlUUdgIbSJpaKL+5PMTM4yBuwCU4QPbYYzTs9x2ZA+7zt41ng+i5+EPtePyDjR4VFTz+7zglL" +
            "w/E+KqN/nscyqLCyrumn4YgfQ3JYnSnz1WLE6q3aD175yweKBj9f9jyqxnLVmEYy9VjmoxuYNRgVmfT6M17" +
            "bT9iL0PJTlJ6UqKHuNRT6ubv37ZSr961Gw+RJhyLUDBt8AD1B8YDdF4OImS+LgGjfujaY1agc4tfrnk4V4Yc" +
            "AXyTRlYwLMC9ATDp9CbiBrlMBmYm88gwGaTR9pbI2KcQ4Kg86jZYc6CxNM34sbMG/1LlmqvqLe+E3IG6ebO" +
            "myVbL+kYK70c1fT5TcmzVwX5O3JGkHHtFoeCmd4Eyyov6QsO1Jewx0gpjp05dqw=="
}
