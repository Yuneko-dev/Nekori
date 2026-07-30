package eu.kanade.tachiyomi.discord

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordProtocolTest {

    @Test
    fun `RPC strings match Discord length constraints`() {
        assertNull(formatRpcString("  "))
        assertEquals("x\u200B", formatRpcString("x"))
        assertEquals("1234567...", formatRpcString("123456789012", maxLength = 10))
    }

    @Test
    fun `presence contains LNReader gaming SDK activity shape`() {
        val activity = DiscordPresence(
            sessionId = "session",
            details = "Novel",
            state = "Reading: Chapter 1",
            largeImage = "external/cover",
            largeText = "Chapter 1",
            smallImage = DiscordProtocol.APP_LOGO_ASSET_ID,
            smallText = "Tsundoku",
            buttons = listOf(
                "Read on Tsundoku" to "https://github.com/tsundoku-otaku",
                "Read chapter" to "https://example.com/novel/chapter-1",
            ),
            startedAt = 1234,
        ).toJson()

        assertEquals(3, activity.getValue("type").jsonPrimitive.content.toInt())
        assertEquals(DiscordProtocol.CLIENT_ID, activity.getValue("application_id").jsonPrimitive.content)
        assertEquals("session", activity.getValue("session_id").jsonPrimitive.content)
        assertEquals("Reading: Chapter 1", activity.getValue("state").jsonPrimitive.content)
        assertEquals("mp:external/cover", activity.getValue("assets").jsonObject["large_image"]?.jsonPrimitive?.content)
        assertEquals(2, activity.getValue("buttons").jsonArray.size)
        assertEquals(
            listOf(
                "https://github.com/tsundoku-otaku",
                "https://example.com/novel/chapter-1",
            ),
            activity.getValue("metadata").jsonObject
                .getValue("button_urls")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `token absolute expiry and authorization header are stable`() {
        val token = DiscordToken(
            accessToken = "access",
            tokenType = "Bearer",
            expiresIn = 60,
            refreshToken = "refresh",
            scope = "scope",
        ).withAbsoluteExpiry(now = 1_000)

        assertEquals(61_000, token.expiresAt)
        assertEquals("Bearer access", token.authorizationHeader())
    }

    @Test
    fun `PKCE challenge matches RFC 7636 vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DiscordProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `OAuth callback rejects mismatched state before accepting code`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscordProtocol.validateCallback(
                expectedState = "expected",
                returnedState = "attacker",
                code = "code",
                error = null,
                errorDescription = null,
            )
        }
        assertEquals(
            "code",
            DiscordProtocol.validateCallback(
                expectedState = "expected",
                returnedState = "expected",
                code = "code",
                error = null,
                errorDescription = null,
            ),
        )
    }

    @Test
    fun `only HTTP URLs are accepted for presence buttons and assets`() {
        assertTrue(isHttpUrl("https://example.com/path"))
        assertTrue(isHttpUrl("http://example.com"))
        assertFalse(isHttpUrl("file:///private"))
        assertFalse(isHttpUrl("javascript:alert(1)"))
        assertFalse(isHttpUrl("not a url"))
        assertEquals("mp:external/cover", normalizeDiscordImage("external/cover"))
        assertEquals(DiscordProtocol.APP_LOGO_ASSET_ID, normalizeDiscordImage(DiscordProtocol.APP_LOGO_ASSET_ID))
    }

    @Test
    fun `OAuth launcher only falls back to browser when Discord deep link fails`() {
        val nativeSuccessCalls = mutableListOf<String>()
        assertTrue(
            launchDiscordAuthorization(
                openNative = { nativeSuccessCalls += "native" },
                openBrowser = { nativeSuccessCalls += "browser" },
            ),
        )
        assertEquals(listOf("native"), nativeSuccessCalls)

        val nativeFailureCalls = mutableListOf<String>()
        assertFalse(
            launchDiscordAuthorization(
                openNative = {
                    nativeFailureCalls += "native"
                    error("Discord is not installed")
                },
                openBrowser = { nativeFailureCalls += "browser" },
            ),
        )
        assertEquals(listOf("native", "browser"), nativeFailureCalls)
    }
}
