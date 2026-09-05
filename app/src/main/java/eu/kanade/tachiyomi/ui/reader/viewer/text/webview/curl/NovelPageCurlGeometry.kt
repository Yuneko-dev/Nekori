/*
 * Curl projection adapted from libreadview's GoogleCurlRenderer.
 * Source: https://github.com/Peyilo/libreadview/blob/bf7756eae91ceb1a6d6428fe25bac8fb1cc81e4a/
 * app/libreadview/src/main/java/org/peyilo/libreadview/turning/render/GoogleCurlRenderer.kt
 *
 * MIT License
 * Copyright (c) 2025 Peyilo
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class NovelPageCurlReadingDirection { LTR, RTL }

enum class NovelPageCurlTurnDirection { FORWARD, BACKWARD }

enum class NovelPageCurlSide { FULL, LEFT, RIGHT }

data class NovelPageCurlMesh(
    val frontVertices: FloatArray,
    val spineX: Float,
    val minX: Float,
    val maxX: Float,
    val edgeX: Float,
    val amount: Float,
)

data class NovelPageCurlBitmapSize(val width: Int, val height: Int, val scale: Float)

data class NovelPageSlideFrame(val sourceOffsetX: Float, val targetOffsetX: Float)

data class NovelPageCurlGestureWidths(val progress: Float, val commit: Float)

object NovelPageCurlGeometry {
    const val MESH_WIDTH = 30
    const val MESH_HEIGHT = 50
    const val VERTEX_FLOAT_COUNT = (MESH_WIDTH + 1) * (MESH_HEIGHT + 1) * 2
    const val DEFAULT_VELOCITY_THRESHOLD = 1_200f
    const val DEFAULT_BITMAP_BUDGET_BYTES = 32L * 1024 * 1024

    fun createMesh(
        width: Float,
        height: Float,
        progress: Float,
        readingDirection: NovelPageCurlReadingDirection,
        turnDirection: NovelPageCurlTurnDirection,
        side: NovelPageCurlSide,
        vertices: FloatArray = FloatArray(VERTEX_FLOAT_COUNT),
    ): NovelPageCurlMesh {
        require(width > 0f && height > 0f)
        require(vertices.size == VERTEX_FLOAT_COUNT)

        val rawAmount = progress.coerceIn(0f, 1f)
        val amount = if (turnDirection == NovelPageCurlTurnDirection.BACKWARD) {
            1f - rawAmount
        } else {
            rawAmount
        }
        val leafWidth = if (side == NovelPageCurlSide.FULL) width else width / 2f
        val spineX = when (side) {
            NovelPageCurlSide.FULL -> if (readingDirection == NovelPageCurlReadingDirection.LTR) 0f else width
            NovelPageCurlSide.LEFT, NovelPageCurlSide.RIGHT -> width / 2f
        }
        val outward = when (side) {
            NovelPageCurlSide.RIGHT -> 1f
            NovelPageCurlSide.LEFT -> -1f
            NovelPageCurlSide.FULL -> if (readingDirection == NovelPageCurlReadingDirection.LTR) 1f else -1f
        }
        val radius = if (amount < RADIUS_RAMP_END) MAX_RADIUS * amount / RADIUS_RAMP_END else MAX_RADIUS
        val movement = max(0f, amount - MOVEMENT_DELAY)
        val displacedColumns = amount * MESH_WIDTH

        fun project(materialDistance: Float, y: Float, output: FloatArray, offset: Int) {
            val gridColumn = materialDistance / leafWidth * MESH_WIDTH
            val depth = radius * sin(PI.toFloat() / (MESH_WIDTH * WAVE_LENGTH) * (gridColumn - displacedColumns)) +
                radius * WAVE_BASELINE
            val gridX = gridColumn / MESH_WIDTH * (1f - radius) - movement
            val perspective = CAMERA_DISTANCE / (CAMERA_DISTANCE + max(depth, MIN_DEPTH))
            val gridY = y / height
            val centeredY = gridY - 0.5f
            val yBend = sign(centeredY) * abs(centeredY) * 2f * depth * Y_BEND_STRENGTH * height
            output[offset] = spineX + outward * perspective * gridX * leafWidth
            output[offset + 1] = y + yBend
        }

        var vertexOffset = 0
        for (row in 0..MESH_HEIGHT) {
            val y = height * row / MESH_HEIGHT
            for (column in 0..MESH_WIDTH) {
                val materialDistance = textureDistance(column, side, readingDirection, leafWidth)
                project(materialDistance, y, vertices, vertexOffset)
                vertexOffset += 2
            }
        }

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        val sample = FloatArray(2)
        for (column in 0..MESH_WIDTH) {
            val materialDistance = leafWidth * column / MESH_WIDTH
            project(materialDistance, height / 2f, sample, 0)
            val x = sample[0]
            minX = min(minX, x)
            maxX = max(maxX, x)
        }
        val edgeX = if (outward > 0f) maxX else minX
        return NovelPageCurlMesh(vertices, spineX, minX, maxX, edgeX, amount)
    }

    private fun textureDistance(
        column: Int,
        side: NovelPageCurlSide,
        readingDirection: NovelPageCurlReadingDirection,
        leafWidth: Float,
    ): Float {
        val half = MESH_WIDTH / 2
        return when (side) {
            NovelPageCurlSide.FULL -> leafWidth * if (readingDirection == NovelPageCurlReadingDirection.LTR) {
                column.toFloat() / MESH_WIDTH
            } else {
                (MESH_WIDTH - column).toFloat() / MESH_WIDTH
            }
            NovelPageCurlSide.RIGHT -> leafWidth * (column - half).coerceAtLeast(0) / half
            NovelPageCurlSide.LEFT -> leafWidth * (half - column).coerceAtLeast(0) / half
        }
    }

    fun horizontalFrame(width: Float, progress: Float, movesLeft: Boolean): NovelPageSlideFrame {
        require(width > 0f)
        val source = width * progress.coerceIn(0f, 1f) * if (movesLeft) -1f else 1f
        return NovelPageSlideFrame(source, source + if (movesLeft) width else -width)
    }

    fun slideFrame(width: Float, progress: Float, movesLeft: Boolean): NovelPageSlideFrame {
        require(width > 0f)
        val amount = progress.coerceIn(0f, 1f)
        return if (movesLeft) {
            NovelPageSlideFrame(-width * amount, 0f)
        } else {
            NovelPageSlideFrame(0f, -width * (1f - amount))
        }
    }

    fun movingShadowEdge(width: Float, movingOffset: Float, progress: Float): Float? {
        if (progress <= 0f || progress >= 1f) return null
        return if (movingOffset < 0f) width + movingOffset else movingOffset
    }

    // Smooth endpoint ramps keep a held turn shaded without a final-frame alpha jump.
    fun shadowOpacity(progress: Float): Float {
        val ramp = (min(progress, 1f - progress) / 0.2f).coerceIn(0f, 1f)
        return ramp * ramp * (3f - 2f * ramp)
    }

    fun settleDuration(progress: Float, commit: Boolean, velocity: Float, width: Float): Long {
        val remaining = abs((if (commit) 1f else 0f) - progress).coerceIn(0f, 1f)
        val distanceDuration = 220f * sqrt(remaining)
        val speedDuration = if (velocity > 0f) 1_000f * remaining * width / velocity else distanceDuration
        return min(distanceDuration, speedDuration).toLong().coerceIn(80L, 220L)
    }

    fun curlsFromRight(readingDirection: NovelPageCurlReadingDirection, turnDirection: NovelPageCurlTurnDirection) =
        (readingDirection == NovelPageCurlReadingDirection.LTR) ==
            (turnDirection == NovelPageCurlTurnDirection.FORWARD)

    fun directedDrag(startX: Float, currentX: Float, curlsFromRight: Boolean): Float =
        (if (curlsFromRight) startX - currentX else currentX - startX).coerceAtLeast(0f)

    fun directedVelocity(velocityX: Float, curlsFromRight: Boolean) =
        if (curlsFromRight) -velocityX else velocityX

    fun logicalDelta(deltaX: Float, readingDirection: NovelPageCurlReadingDirection): Int {
        val ltrDelta = if (deltaX < 0f) 1 else -1
        return if (readingDirection == NovelPageCurlReadingDirection.LTR) ltrDelta else -ltrDelta
    }

    fun shouldCommit(
        dragDistance: Float,
        leafWidth: Float,
        directedVelocity: Float,
        velocityThreshold: Float = DEFAULT_VELOCITY_THRESHOLD,
    ) = leafWidth > 0f && (dragDistance >= leafWidth / 3f || directedVelocity >= velocityThreshold)

    fun gestureWidths(viewportWidth: Float, doubleSpread: Boolean, curlEffect: Boolean): NovelPageCurlGestureWidths {
        require(viewportWidth > 0f)
        val commitWidth = if (doubleSpread && curlEffect) viewportWidth / 2f else viewportWidth
        return NovelPageCurlGestureWidths(progress = viewportWidth, commit = commitWidth)
    }

    fun bitmapSize(
        width: Int,
        height: Int,
        budgetBytes: Long = DEFAULT_BITMAP_BUDGET_BYTES,
    ): NovelPageCurlBitmapSize {
        require(width > 0 && height > 0 && budgetBytes >= BYTES_PER_PIXEL * BITMAP_COUNT)
        val scale = min(1.0, sqrt(budgetBytes / (width.toDouble() * height * BYTES_PER_PIXEL * BITMAP_COUNT)))
        return NovelPageCurlBitmapSize(
            max(1, floor(width * scale).toInt()),
            max(1, floor(height * scale).toInt()),
            scale.toFloat(),
        )
    }

    private const val MAX_RADIUS = 0.15f
    private const val RADIUS_RAMP_END = 0.2f
    private const val MOVEMENT_DELAY = 0.1f
    private const val WAVE_LENGTH = 0.7f
    private const val WAVE_BASELINE = 1.1f
    private const val CAMERA_DISTANCE = -3f
    private const val MIN_DEPTH = 0.001f
    private const val Y_BEND_STRENGTH = 0.15f
    private const val BYTES_PER_PIXEL = 4L
    private const val BITMAP_COUNT = 2L
}
