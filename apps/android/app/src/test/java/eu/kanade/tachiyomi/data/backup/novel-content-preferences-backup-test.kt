@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.ui.reader.setting.CodeSnippet
import eu.kanade.tachiyomi.ui.reader.setting.NovelContentPreferences
import eu.kanade.tachiyomi.ui.reader.setting.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReplacementTarget
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelGuidelinesAssignment
import tachiyomi.domain.translation.model.NovelGuidelinesAssignments
import tachiyomi.domain.translation.model.NovelGuidelinesChoice
import tachiyomi.domain.translation.model.UserGuidelines
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

class NovelContentPreferencesBackupTest {
    private val target = ReplacementTarget(Long.MAX_VALUE, "/novel//one/#part")

    @Test
    fun `gzip native protobuf stream round trips scoped string preferences without parent database rows`() = runTest {
        val before = InMemoryPreferenceStore()
        val snippets = listOf(CodeSnippet("Poem", "  p { color: red }\n", scope = "NOVEL", target = target))
        val assignments = listOf(
            NovelGuidelinesAssignment(
                target,
                NovelAiTask.TRANSLATION,
                NovelGuidelinesChoice("custom", text = "\nexact "),
            ),
        )
        before.getString(CodeSnippet.CSS_KEY).set(CodeSnippet.encode(snippets))
        val scripts = snippets.map { it.copy(code = "  window.test = 'exact';\n", runOnAppend = true) }
        before.getString(CodeSnippet.JS_KEY).set(CodeSnippet.encode(scripts))
        val rules = listOf(RegexReplacement("Names", "old", "new", scope = "NOVEL", target = target))
        before.getString(RegexReplacement.PREFERENCE_KEY).set(Json.encodeToString(rules))
        val library = listOf(UserGuidelines("default", "Default", "  saved default\n"))
        before.getString(UserGuidelines.PREFERENCE_KEY).set(Json.encodeToString(library))
        before.getString("translation_ai_guidelines").set("missing-reference")
        before.getString("chapter_summary_ai_guidelines").set("")
        before.getString(NovelGuidelinesAssignments.PREFERENCE_KEY).set(NovelGuidelinesAssignments.encode(assignments))
        val backup = PreferenceBackupCreator(mockk(), before).createApp(false)

        // Stream canonical Backup field 104 records using the production protobuf model, then read
        // the gzip file through the same field reader used by BackupRestorer and file validation.
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { output ->
            backup.forEach { preference ->
                output.write(
                    ProtoBuf.encodeToByteArray(
                        Backup.serializer(),
                        Backup(backupManga = emptyList(), backupPreferences = listOf(preference)),
                    ),
                )
            }
        }
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(compressed.toByteArray()) }
        val decoded = mutableListOf<BackupPreference>()
        BackupProtoReader(context).read(uri) { fieldNumber, data ->
            assertEquals(104, fieldNumber)
            decoded += ProtoBuf.decodeFromByteArray(BackupPreference.serializer(), data)
        }
        assertEquals(backup, decoded)
        assertTrue(decoded.all { it.value is StringPreferenceValue })
        val after = InMemoryPreferenceStore()
        val errors = PreferenceRestorer(mockk(), mockk(), after).restorePreferences(decoded, after)
        assertTrue(errors.isEmpty())
        assertEquals(snippets, CodeSnippet.decode(after.getString(CodeSnippet.CSS_KEY).get()))
        assertEquals(scripts, CodeSnippet.decode(after.getString(CodeSnippet.JS_KEY).get()))
        assertEquals(rules, RegexReplacement.decode(after.getString(RegexReplacement.PREFERENCE_KEY).get()))
        assertEquals(library, UserGuidelines.decode(after.getString(UserGuidelines.PREFERENCE_KEY).get()))
        assertEquals("missing-reference", after.getString("translation_ai_guidelines").get())
        assertEquals("", after.getString("chapter_summary_ai_guidelines").get())
        assertEquals(
            assignments,
            NovelGuidelinesAssignments.decode(after.getString(NovelGuidelinesAssignments.PREFERENCE_KEY).get()),
        )
    }

    @Test
    fun `absent collections preserve local and explicit empty clears only present key`() = runTest {
        val store = InMemoryPreferenceStore()
        val css = CodeSnippet.encode(listOf(CodeSnippet("Keep", "unchanged")))
        store.getString(CodeSnippet.CSS_KEY).set(css)
        store.getString(CodeSnippet.JS_KEY).set(css)
        PreferenceRestorer(mockk(), mockk(), store).restorePreferences(
            listOf(BackupPreference(CodeSnippet.JS_KEY, StringPreferenceValue("[]"))),
            store,
        )
        assertEquals(css, store.getString(CodeSnippet.CSS_KEY).get())
        assertEquals("[]", store.getString(CodeSnippet.JS_KEY).get())
    }

    @Test
    fun `restore repairs duplicate legacy ids preserving all code and order`() = runTest {
        val store = InMemoryPreferenceStore()
        val legacy = """[{"title":"a","code":"  a\n","id":"same"},{"title":"b","code":"b","id":"same"}]"""
        PreferenceRestorer(mockk(), mockk(), store).restorePreferences(
            listOf(BackupPreference(CodeSnippet.CSS_KEY, StringPreferenceValue(legacy))),
            store,
        )
        val restored = CodeSnippet.decode(store.getString(CodeSnippet.CSS_KEY).get())
        assertEquals(listOf("  a\n", "b"), restored.map { it.code })
        assertEquals(2, restored.map { it.id }.distinct().size)
        assertEquals("same", restored.first().id)
    }

    @Test
    fun `restore writes missing legacy snippet ids once as persistent UUIDs`() = runTest {
        val backing = InMemoryPreferenceStore()
        var writes = 0
        val store = object : PreferenceStore by backing {
            override fun getString(key: String, defaultValue: String): Preference<String> {
                val preference = backing.getString(key, defaultValue)
                return object : Preference<String> by preference {
                    override fun set(value: String) {
                        writes++
                        preference.set(value)
                    }
                }
            }
        }
        val legacy = """[{"title":"a","code":"exact\n"},{"title":"a","code":"exact\n"}]"""
        val errors = PreferenceRestorer(mockk(), mockk(), store).restorePreferences(
            listOf(BackupPreference(CodeSnippet.CSS_KEY, StringPreferenceValue(legacy))),
            store,
        )
        assertTrue(errors.isEmpty())
        val raw = store.getString(CodeSnippet.CSS_KEY).get()
        val restored = CodeSnippet.decode(raw)
        assertEquals(listOf("exact\n", "exact\n"), restored.map { it.code })
        assertEquals(2, restored.map { UUID.fromString(it.id) }.distinct().size)
        assertEquals(restored, CodeSnippet.decode(raw))
        assertEquals(raw, store.getString(CodeSnippet.CSS_KEY).get())
        assertEquals(1, writes)
    }

    @Test
    fun `malformed unknown and wrong typed collections retain local and report each key`() = runTest {
        mockkStatic(Log::class)
        try {
            every { Log.e(any(), any()) } returns 0
            val store = InMemoryPreferenceStore()
            val keys = NovelContentPreferences.collectionKeys.toList()
            keys.forEach { store.getString(it).set("existing $it") }
            val invalid = keys.map { BackupPreference(it, StringPreferenceValue("broken")) } +
                BackupPreference("unrelated", StringPreferenceValue("restored"))
            val errors = PreferenceRestorer(mockk(), mockk(), store).restorePreferences(invalid, store)
            assertEquals(keys.toSet(), errors.toSet())
            keys.forEach { assertEquals("existing $it", store.getString(it).get()) }
            assertEquals("restored", store.getString("unrelated").get())
            val typeErrors = PreferenceRestorer(mockk(), mockk(), store).restorePreferences(
                listOf(BackupPreference(CodeSnippet.CSS_KEY, IntPreferenceValue(3))),
                store,
            )
            assertEquals(listOf(CodeSnippet.CSS_KEY), typeErrors)
            assertEquals("existing ${CodeSnippet.CSS_KEY}", store.getString(CodeSnippet.CSS_KEY).get())
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `library restore keeps edited Default and missing shared references valid`() {
        val library = listOf(UserGuidelines("default", "Default", "  user's text\n"))
        val restored = NovelContentPreferences.validatedRestoreValue(
            UserGuidelines.PREFERENCE_KEY,
            Json.encodeToString(library),
        )
        assertEquals(library, UserGuidelines.decode(restored))
        val assignment = listOf(
            NovelGuidelinesAssignment(target, NovelAiTask.CHAPTER_SUMMARY, NovelGuidelinesChoice("shared", "missing")),
        )
        assertEquals(
            assignment,
            NovelGuidelinesAssignments.decode(
                NovelContentPreferences.validatedRestoreValue(
                    NovelGuidelinesAssignments.PREFERENCE_KEY,
                    NovelGuidelinesAssignments.encode(assignment),
                ),
            ),
        )
    }
}
