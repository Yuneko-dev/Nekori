@file:Suppress("ktlint:standard:filename")

package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.model.NovelAiTask
import tachiyomi.domain.translation.model.NovelGuidelinesChoice
import tachiyomi.domain.translation.model.NovelTarget
import tachiyomi.domain.translation.model.UserGuidelines
import tachiyomi.domain.translation.service.TranslationPreferences

class NovelAiSettingsStoreTest {
    private val preferences = TranslationPreferences(InMemoryPreferenceStore())
    private val store = AiSettingsStore(preferences, Json { ignoreUnknownKeys = true })

    @Test
    fun `invalid user JSON is absent from resolver errors and their causes`() {
        preferences.userGuidelinesJson().set("secret authored guidelines")
        val libraryFailure = shouldThrow<IllegalArgumentException> { store.guidelines() }
        libraryFailure.toString() shouldBe "java.lang.IllegalArgumentException: Invalid saved guidelines library"
        libraryFailure.cause shouldBe null
        preferences.novelGuidelinesJson().set("secret authored custom text")
        val assignmentFailure = shouldThrow<IllegalArgumentException> { store.assignments() }
        assignmentFailure.toString() shouldBe "java.lang.IllegalArgumentException: Invalid saved novel guidelines"
        assignmentFailure.cause shouldBe null
    }

    @Test
    fun `deleting shared guidelines keeps refs and resolves the saved Default`() {
        store.saveGuidelines(UserGuidelines.DEFAULT.copy(guidelines = "Default edited"))
        store.saveGuidelines(names)
        saveChoice(NovelGuidelinesChoice("shared", guidelinesId = names.id))
        val raw = preferences.novelGuidelinesJson().get()
        store.deleteGuidelines(names.id)
        preferences.novelGuidelinesJson().get() shouldBe raw
        store.effectiveGuidelines("global", target) shouldBe "Default edited"
        store.effectiveGuidelines(names.id) shouldBe "Default edited"
        store.effectiveGuidelines("") shouldBe ""
        store.saveGuidelines(names)
        store.effectiveGuidelines("global", target) shouldBe "Keep names"
    }

    @Test
    fun `target task and explicit empty choices remain independent`() {
        store.saveGuidelines(names)
        store.effectiveGuidelines(names.id, target) shouldBe "Keep names"
        saveChoice(NovelGuidelinesChoice("custom", text = ""))
        store.effectiveGuidelines(names.id, target) shouldBe ""
        store.effectiveGuidelines(names.id, target.copy(path = "/a/")) shouldBe "Keep names"
        store.effectiveGuidelines(names.id, target.copy(source = 2)) shouldBe "Keep names"
        store.effectiveGuidelines(names.id, target, NovelAiTask.CHAPTER_SUMMARY) shouldBe "Keep names"
        saveChoice(null)
        preferences.novelGuidelinesJson().get() shouldBe "[]"
        store.effectiveGuidelines(names.id, target) shouldBe "Keep names"
    }

    @Test
    fun `unused invalid library does not block explicit none custom or blank Global`() {
        preferences.userGuidelinesJson().set("broken library")
        store.effectiveGuidelines("") shouldBe ""
        saveChoice(NovelGuidelinesChoice("none"))
        store.effectiveGuidelines(names.id, target) shouldBe ""
        saveChoice(NovelGuidelinesChoice("custom", text = "  Own text\n"))
        store.effectiveGuidelines(names.id, target) shouldBe "  Own text\n"
        saveChoice(NovelGuidelinesChoice("shared", guidelinesId = names.id))
        shouldThrow<IllegalArgumentException> { store.effectiveGuidelines(names.id, target) }
        preferences.userGuidelinesJson().get() shouldBe "broken library"
    }

    @Test
    fun `invalid assignment blocks targeted resolution and cannot overwrite cached good data`() {
        saveChoice(NovelGuidelinesChoice("custom", text = "before"))
        store.effectiveGuidelines("", target) shouldBe "before"
        preferences.novelGuidelinesJson().set("broken assignments")
        shouldThrow<IllegalArgumentException> { store.effectiveGuidelines("", target) }
        shouldThrow<IllegalArgumentException> { saveChoice(NovelGuidelinesChoice("none")) }
        preferences.novelGuidelinesJson().get() shouldBe "broken assignments"
        store.effectiveGuidelines("") shouldBe ""
    }

    @Test
    fun `stale novel and library drafts do not overwrite changes in another scope`() {
        val original = preferences.novelGuidelinesJson().get()
        saveChoice(NovelGuidelinesChoice("none"))
        store.saveChoice(target.copy(path = "/b"), NovelAiTask.TRANSLATION, null, original) shouldBe false
        store.choice(target, NovelAiTask.TRANSLATION) shouldBe NovelGuidelinesChoice("none")
        val libraryOriginal = preferences.userGuidelinesJson().get()
        store.saveGuidelines(names)
        store.saveGuidelines(names.copy(guidelines = "stale"), libraryOriginal) shouldBe false
        store.guidelines().single { it.id == names.id } shouldBe names
        preferences.userGuidelinesJson().set("broken library")
        shouldThrow<IllegalArgumentException> { store.saveGuidelines(names) }
        shouldThrow<IllegalArgumentException> { store.deleteGuidelines(names.id) }
        preferences.userGuidelinesJson().get() shouldBe "broken library"
    }

    private val target = NovelTarget(Long.MAX_VALUE, "/a")

    private fun saveChoice(choice: NovelGuidelinesChoice?) = store.saveChoice(
        target,
        NovelAiTask.TRANSLATION,
        choice,
        preferences.novelGuidelinesJson().get(),
    )

    private val names = UserGuidelines("custom", "Names", "Keep names")
}
