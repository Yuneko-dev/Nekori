@file:Suppress("ktlint:standard:filename")

package tachiyomi.domain.translation.model

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelGuidelinesTest {
    @Test
    fun `exact identities and custom text round trip without number precision loss`() {
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE).map {
            NovelGuidelinesAssignment(
                NovelTarget(it, "//日本/novel/?q=1#章/"),
                NovelAiTask.TRANSLATION,
                NovelGuidelinesChoice("custom", text = "  Keep\n\tall bytes  "),
            )
        }
        NovelGuidelinesAssignments.decode(NovelGuidelinesAssignments.encode(values)) shouldBe values
    }

    @Test
    fun `invalid and future formats are rejected without dropping entries`() {
        val target = """"target":{"source":1,"path":"/a"},"task":"TRANSLATION","choice":"""
        val invalid = listOf(
            "{}", "null", "", "[false]",
            """[{$target{"mode":"inherit"}}]""",
            """[{$target{"mode":"custom"}}]""",
            """[{$target{"mode":"custom","text":null}}]""",
            """[{$target{"mode":"none","text":null}}]""",
            """[{$target{"mode":"shared","guidelinesId":" "}}]""",
            """[{$target{"mode":"shared","guidelinesId":"g","text":"mixed"}}]""",
            """[{$target{"mode":"future","text":"preserve"}}]""",
            """[{$target{"mode":"none"},"future":true}]""",
            """[{"target":{"source":"1","path":"/a"},"task":"TRANSLATION","choice":{"mode":"none"}}]""",
            """[{"target":{"source":1,"path":" "},"task":"TRANSLATION","choice":{"mode":"none"}}]""",
            """[{"target":{"source":1},"task":"TRANSLATION","choice":{"mode":"none"}}]""",
            """[{"target":{"source":1,"path":"a"},"task":"FUTURE","choice":{"mode":"none"}}]""",
        )
        invalid.forEach { raw -> shouldThrowAny { NovelGuidelinesAssignments.decode(raw) } }
    }

    @Test
    fun `a target task pair is unique but task and trailing slash stay distinct`() {
        val value = NovelGuidelinesAssignment(
            NovelTarget(1, "/a"),
            NovelAiTask.TRANSLATION,
            NovelGuidelinesChoice("none"),
        )
        shouldThrowAny { NovelGuidelinesAssignments.encode(listOf(value, value)) }
        val values = listOf(
            value,
            value.copy(task = NovelAiTask.CHAPTER_SUMMARY),
            value.copy(target = NovelTarget(1, "/a/")),
        )
        NovelGuidelinesAssignments.decode(NovelGuidelinesAssignments.encode(values)) shouldBe values
    }

    @Test
    fun `library validates identities and preserves edited Default`() {
        val saved = UserGuidelines.DEFAULT.copy(guidelines = "Saved Default")
        val definitions = listOf(saved, UserGuidelines("g", "Names", " "))
        definitions.resolve("missing") shouldBe saved
        definitions.resolve("") shouldBe UserGuidelines.DEFAULT
        definitions.resolve("g").guidelines shouldBe " "
        UserGuidelines.decode("[]") shouldBe listOf(UserGuidelines.DEFAULT)
        shouldThrowAny { UserGuidelines.decode("broken") }
        shouldThrowAny { UserGuidelines.decode("""[{"id":"g","name":"A"},{"id":"g","name":"B"}]""") }
        shouldThrowAny { UserGuidelines.decode("""[{"id":"","name":"A"}]""") }
        shouldThrowAny { UserGuidelines.decode("""[{"id":"g","name":"A","future":1}]""") }
    }
}
