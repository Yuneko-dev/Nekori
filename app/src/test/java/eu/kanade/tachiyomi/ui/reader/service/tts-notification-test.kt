@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.ui.reader.service

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TtsNotificationTest {

    @Test
    fun `media is the default and the upstream classic preference is retained`() {
        val store = InMemoryPreferenceStore()
        assertFalse(ReaderPreferences(store).novelTtsLegacyNotification.get())
        store.getBoolean("pref_novel_tts_legacy_notification", false).set(true)
        assertTrue(ReaderPreferences(store).novelTtsLegacyNotification.get())
    }

    @Test
    fun `switching to classic releases media controls and switching back republishes paused progress`() {
        val session = mockk<MediaSession>(relaxed = true)
        val service = mockk<TtsPlaybackService>(relaxed = true)
        val fields = mapOf("mediaSession" to session, "paragraphIndex" to 3, "paragraphCount" to 8, "isPaused" to true)
        for ((name, value) in fields) {
            TtsPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        every { service["updateMediaSession"](any<Boolean>()) } answers { callOriginal() }
        every { service["paragraphProgressLabel"]() } returns "Paragraph 4 of 8"
        val update = TtsPlaybackService::class.java.getDeclaredMethod(
            "updateMediaSession",
            Boolean::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }

        mockkConstructor(MediaMetadata.Builder::class, PlaybackState.Builder::class)
        try {
            every { anyConstructed<MediaMetadata.Builder>().putString(any(), any()) } answers {
                self as MediaMetadata.Builder
            }
            every { anyConstructed<MediaMetadata.Builder>().putLong(any(), any()) } answers
                { self as MediaMetadata.Builder }
            every { anyConstructed<MediaMetadata.Builder>().build() } returns mockk()
            every { anyConstructed<PlaybackState.Builder>().setActions(any()) } answers
                { self as PlaybackState.Builder }
            every { anyConstructed<PlaybackState.Builder>().setState(any(), any(), any()) } answers {
                self as PlaybackState.Builder
            }
            every { anyConstructed<PlaybackState.Builder>().build() } returns mockk()

            update.invoke(service, false)
            update.invoke(service, true)
            update.invoke(service, false)

            verifyOrder {
                session.isActive = true
                session.setMetadata(any())
                session.setPlaybackState(any())
                session.isActive = false
                session.setPlaybackState(null)
                session.setMetadata(null)
                session.isActive = true
                session.setMetadata(any())
                session.setPlaybackState(any())
            }
            verify(exactly = 2) {
                anyConstructed<PlaybackState.Builder>().setState(PlaybackState.STATE_PAUSED, 3_000L, 0f)
                anyConstructed<MediaMetadata.Builder>().putLong(MediaMetadata.METADATA_KEY_DURATION, 8_000L)
            }
        } finally {
            unmockkConstructor(MediaMetadata.Builder::class, PlaybackState.Builder::class)
        }
    }
}
