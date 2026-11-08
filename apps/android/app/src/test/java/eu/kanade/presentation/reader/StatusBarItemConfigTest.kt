package eu.kanade.presentation.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusBarItemConfigTest {
    @Test
    fun `malformed and duplicate saved elements recover one of each item`() {
        assertEquals(DefaultStatusBarOrder, "invalid".deserializeStatusBarOrder())
        val order = "[\"word_count\",\"word_count\",\"unknown\"]".deserializeStatusBarOrder()
        assertEquals(StatusBarItem.WORD_COUNT, order.first())
        assertEquals(DefaultStatusBarOrder.size, order.size)
        assertEquals(DefaultStatusBarOrder.toSet(), order.toSet())
    }

    @Test
    fun `old saved order gains word count without moving existing items`() {
        val order = "[\"battery\",\"time\",\"chapter\",\"progress\"]".deserializeStatusBarOrder()
        assertEquals(StatusBarItem.BATTERY, order.first())
        assertEquals(StatusBarItem.WORD_COUNT, order.last())
        assertEquals(DefaultStatusBarOrder.toSet(), order.toSet())
    }

    @Test
    fun `custom word count order preserves other items and round trips`() {
        val order = listOf(StatusBarItem.WORD_COUNT) + DefaultStatusBarOrder.filter { it != StatusBarItem.WORD_COUNT }
        assertEquals(StatusBarItem.WORD_COUNT, order.first())
        assertEquals(DefaultStatusBarOrder.filter { it != StatusBarItem.WORD_COUNT }, order.drop(1))
        assertEquals(order, order.serializeStatusBarOrder().deserializeStatusBarOrder())
    }
}
