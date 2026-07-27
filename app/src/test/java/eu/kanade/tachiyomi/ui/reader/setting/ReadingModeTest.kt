package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingModeTest {

    @Test
    fun `only novel mode remains`() {
        ReadingMode.entries.shouldContainExactly(listOf(ReadingMode.NOVEL))
    }

    @Test
    fun `legacy and missing preferences resolve to novel`() {
        ReadingMode.fromPreference(null) shouldBe ReadingMode.NOVEL
        ReadingMode.fromPreference(0x00000001) shouldBe ReadingMode.NOVEL
        ReadingMode.fromPreference(0x00000005) shouldBe ReadingMode.NOVEL
        ReadingMode.fromPreference(0x00000006) shouldBe ReadingMode.NOVEL
    }
}
