package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelPageCurlGeometryTest {

    @Test
    fun `single page uses the screen edge as its fixed spine`() {
        val forward = mesh(0f, NovelPageCurlTurnDirection.FORWARD, NovelPageCurlSide.FULL)
        val previousStart = mesh(0f, NovelPageCurlTurnDirection.BACKWARD, NovelPageCurlSide.FULL)
        val previousEnd = mesh(1f, NovelPageCurlTurnDirection.BACKWARD, NovelPageCurlSide.FULL)

        assertEquals(0f, forward.spineX, EPSILON)
        assertEquals(0f, previousStart.spineX, EPSILON)
        assertEquals(0f, vertexX(forward, 0), EPSILON)
        assertEquals(200f, vertexX(forward, NovelPageCurlGeometry.MESH_WIDTH), FLAT_EPSILON)
        assertTrue(previousStart.maxX <= previousStart.spineX)
        assertEquals(0f, vertexX(previousEnd, 0), EPSILON)
        assertEquals(200f, vertexX(previousEnd, NovelPageCurlGeometry.MESH_WIDTH), FLAT_EPSILON)
    }

    @Test
    fun `double page uses the center as its fixed spine`() {
        val right = mesh(0f, NovelPageCurlTurnDirection.FORWARD, NovelPageCurlSide.RIGHT)
        val left = mesh(1f, NovelPageCurlTurnDirection.BACKWARD, NovelPageCurlSide.LEFT)
        val half = NovelPageCurlGeometry.MESH_WIDTH / 2

        assertEquals(100f, right.spineX, EPSILON)
        assertEquals(100f, left.spineX, EPSILON)
        assertEquals(100f, vertexX(right, half), EPSILON)
        assertEquals(200f, vertexX(right, NovelPageCurlGeometry.MESH_WIDTH), FLAT_EPSILON)
        assertEquals(0f, vertexX(left, 0), FLAT_EPSILON)
        assertEquals(100f, vertexX(left, half), EPSILON)
    }

    @Test
    fun `projection matches libreadview Google curl landmarks`() {
        val halfTurn = mesh(0.5f, NovelPageCurlTurnDirection.FORWARD, NovelPageCurlSide.FULL)

        assertEquals(-81.293f, vertexX(halfTurn, 0), 0.01f)
        assertEquals(5.291f, vertexX(halfTurn, 15), 0.01f)
        assertEquals(99.348f, vertexX(halfTurn, 30), 0.01f)
        assertEquals(-2.475f, vertexY(halfTurn, 15, 0), 0.01f)
    }

    @Test
    fun `mesh columns never reverse or overlap text`() {
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            val page = mesh(progress, NovelPageCurlTurnDirection.FORWARD, NovelPageCurlSide.FULL)
            for (column in 1..NovelPageCurlGeometry.MESH_WIDTH) {
                assertTrue(vertexX(page, column) > vertexX(page, column - 1))
            }
        }
    }

    @Test
    fun `single page edge can cross the spine at the end`() {
        val end = mesh(1f, NovelPageCurlTurnDirection.FORWARD, NovelPageCurlSide.FULL)

        assertTrue(end.edgeX < end.spineX)
    }

    @Test
    fun `double page mesh remains visible after crossing the center spine`() {
        val end = mesh(1f, NovelPageCurlTurnDirection.FORWARD, NovelPageCurlSide.RIGHT)

        assertTrue(end.minX < 0f)
        assertTrue(end.maxX > 0f && end.maxX < end.spineX)
    }

    @Test
    fun `physical swipe maps to logical direction`() {
        assertEquals(1, NovelPageCurlGeometry.logicalDelta(-20f, NovelPageCurlReadingDirection.LTR))
        assertEquals(-1, NovelPageCurlGeometry.logicalDelta(20f, NovelPageCurlReadingDirection.LTR))
        assertEquals(-1, NovelPageCurlGeometry.logicalDelta(-20f, NovelPageCurlReadingDirection.RTL))
        assertEquals(1, NovelPageCurlGeometry.logicalDelta(20f, NovelPageCurlReadingDirection.RTL))
    }

    @Test
    fun `horizontal swipe and slide keep their existing card motion`() {
        assertEquals(NovelPageSlideFrame(-50f, 150f), NovelPageCurlGeometry.horizontalFrame(200f, 0.25f, true))
        assertEquals(NovelPageSlideFrame(0f, -150f), NovelPageCurlGeometry.slideFrame(200f, 0.25f, false))
    }

    @Test
    fun `moving shadow is absent on both settled frames`() {
        assertEquals(null, NovelPageCurlGeometry.movingShadowEdge(200f, 0f, 0f))
        assertEquals(null, NovelPageCurlGeometry.movingShadowEdge(200f, -200f, 1f))
        assertEquals(150f, NovelPageCurlGeometry.movingShadowEdge(200f, -50f, 0.25f))
    }

    private fun mesh(progress: Float, turn: NovelPageCurlTurnDirection, side: NovelPageCurlSide) =
        NovelPageCurlGeometry.createMesh(200f, 100f, progress, NovelPageCurlReadingDirection.LTR, turn, side)

    private fun vertexX(mesh: NovelPageCurlMesh, column: Int, row: Int = 25): Float {
        return mesh.frontVertices[(row * (NovelPageCurlGeometry.MESH_WIDTH + 1) + column) * 2]
    }

    private fun vertexY(mesh: NovelPageCurlMesh, column: Int, row: Int): Float {
        return mesh.frontVertices[(row * (NovelPageCurlGeometry.MESH_WIDTH + 1) + column) * 2 + 1]
    }

    private companion object {
        const val EPSILON = 0.001f
        const val FLAT_EPSILON = 0.1f
    }
}
