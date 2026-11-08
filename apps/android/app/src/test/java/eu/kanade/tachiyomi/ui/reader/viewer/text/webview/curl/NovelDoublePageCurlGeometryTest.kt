package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelDoublePageCurlGeometryTest {

    @Test
    fun `right leaf progresses through all four viewport quarters`() {
        val frames = listOf(0.25f, 0.5f, 0.75f, 1f).map {
            NovelDoublePageCurlGeometry.create(400f, it, turnsRightLeaf = true)
        }

        assertTrue(frames[0].screenX.max() > 300f)
        assertTrue(frames[1].backFacing.any { it })
        assertTrue(frames[2].screenX.min() < 200f)
        assertEquals(0f, frames[3].screenX.last(), EPSILON)
        assertEquals(200f, frames[3].screenX.first(), EPSILON)
        assertTrue(frames[3].backFacing.all { it })
    }

    @Test
    fun `target back replaces only the turned leaf`() {
        val start = NovelDoublePageCurlGeometry.create(400f, 0f, turnsRightLeaf = true)
        val end = NovelDoublePageCurlGeometry.create(400f, 1f, turnsRightLeaf = true)

        assertTrue(start.backFacing.none { it })
        assertTrue(end.backFacing.all { it })
        assertEquals(200f, start.screenX.first(), EPSILON)
        assertEquals(400f, start.screenX.last(), EPSILON)

        assertEquals(200f, NovelDoublePageCurlGeometry.textureX(400f, 0f, true, false), EPSILON)
        assertEquals(400f, NovelDoublePageCurlGeometry.textureX(400f, 1f, true, false), EPSILON)
        assertEquals(200f, NovelDoublePageCurlGeometry.textureX(400f, 0f, true, true), EPSILON)
        assertEquals(0f, NovelDoublePageCurlGeometry.textureX(400f, 1f, true, true), EPSILON)
    }

    @Test
    fun `left turn mirrors right turn`() {
        val right = NovelDoublePageCurlGeometry.create(400f, 0.63f, turnsRightLeaf = true)
        val left = NovelDoublePageCurlGeometry.create(400f, 0.63f, turnsRightLeaf = false)

        right.screenX.indices.forEach { index ->
            assertEquals(400f - right.screenX[index], left.screenX[index], EPSILON)
        }
    }

    @Test
    fun `page lift fades only while settling flat`() {
        val start = NovelDoublePageCurlGeometry.create(400f, 0f, true)
        val middle = NovelDoublePageCurlGeometry.create(400f, 0.5f, true)
        val crossedSpine = NovelDoublePageCurlGeometry.create(400f, 0.75f, true)
        val end = NovelDoublePageCurlGeometry.create(400f, 1f, true)

        assertEquals(0f, start.lift, EPSILON)
        assertEquals(1f, middle.lift, EPSILON)
        assertTrue(crossedSpine.lift > 0.7f)
        assertEquals(0f, end.lift, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
