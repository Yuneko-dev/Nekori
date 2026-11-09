package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtectedMediaPermissionPolicyTest {

    private val protectedMedia = "android.webkit.resource.PROTECTED_MEDIA_ID"

    @Test
    fun `grants an armed protected-media request from the current plugin origin`() {
        val pluginOrigin = protectedMediaOrigin("https", "novels.example", -1)
        assertTrue(
            canGrantProtectedMediaPlayback(
                armed = true,
                requestOrigin = protectedMediaOrigin("HTTPS", "NOVELS.EXAMPLE", 443),
                documentOrigin = pluginOrigin,
                resources = listOf(protectedMedia),
                protectedMediaResource = protectedMedia,
            ),
        )
    }

    @Test
    fun `rejects a different origin unarmed and mixed resource requests`() {
        val pluginOrigin = protectedMediaOrigin("https", "novels.example", -1)
        val valid = { armed: Boolean, origin: ProtectedMediaOrigin?, resources: List<String> ->
            canGrantProtectedMediaPlayback(armed, origin, pluginOrigin, resources, protectedMedia)
        }

        assertFalse(valid(false, pluginOrigin, listOf(protectedMedia)))
        assertFalse(valid(true, protectedMediaOrigin("https", "media.example", -1), listOf(protectedMedia)))
        assertFalse(valid(true, protectedMediaOrigin("https", "novels.example", 8443), listOf(protectedMedia)))
        assertFalse(valid(true, pluginOrigin, listOf(protectedMedia, "camera")))
    }
}
