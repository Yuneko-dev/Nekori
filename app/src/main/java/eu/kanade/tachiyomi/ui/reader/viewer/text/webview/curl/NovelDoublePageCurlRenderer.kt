package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Vertical-cylinder composition of source <A|B> and target <C|D>. */
internal class NovelDoublePageCurlRenderer {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private val screenX = FloatArray(NovelDoublePageCurlGeometry.STRIP_COUNT + 1)
    private val backFacing = BooleanArray(NovelDoublePageCurlGeometry.STRIP_COUNT)
    private val curveShade = FloatArray(NovelDoublePageCurlGeometry.STRIP_COUNT)

    fun draw(
        canvas: Canvas,
        source: Bitmap,
        target: Bitmap,
        width: Float,
        height: Float,
        progress: Float,
        side: NovelPageCurlSide,
    ) {
        val turnsRightLeaf = side == NovelPageCurlSide.RIGHT
        drawBackground(canvas, source, target, width, height, turnsRightLeaf)
        NovelDoublePageCurlGeometry.fill(
            width,
            progress,
            turnsRightLeaf,
            screenX,
            backFacing,
            curveShade,
        )
        drawTargetShadow(canvas, width, height, turnsRightLeaf, NovelPageCurlGeometry.shadowOpacity(progress))
        drawStrips(canvas, source, target, width, height, turnsRightLeaf, drawBack = false)
        drawStrips(canvas, source, target, width, height, turnsRightLeaf, drawBack = true)
    }

    private fun drawBackground(
        canvas: Canvas,
        source: Bitmap,
        target: Bitmap,
        width: Float,
        height: Float,
        right: Boolean,
    ) {
        val spine = width / 2f
        canvas.withClip(if (right) 0f else spine, if (right) spine else width, height) {
            drawPage(source, width, height)
        }
        canvas.withClip(if (right) spine else 0f, if (right) width else spine, height) {
            drawPage(target, width, height)
        }
    }

    private fun drawStrips(
        canvas: Canvas,
        source: Bitmap,
        target: Bitmap,
        width: Float,
        height: Float,
        right: Boolean,
        drawBack: Boolean,
    ) {
        val bitmap = if (drawBack) target else source
        for (index in 0 until NovelDoublePageCurlGeometry.STRIP_COUNT) {
            if (backFacing[index] != drawBack) continue
            val start = screenX[index]
            val end = screenX[index + 1]
            val left = min(start, end)
            val rightEdge = max(start, end)
            if (rightEdge <= 0f || left >= width || rightEdge - left < EPSILON) continue
            setSourceRect(bitmap, index, right, drawBack)
            destinationRect.set(max(0f, left), 0f, min(width, rightEdge), height)
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint)
            val alpha = (curveShade[index] * CURVE_SHADE_ALPHA).toInt()
            if (alpha > 0) {
                shadePaint.color = Color.argb(alpha, 0, 0, 0)
                canvas.drawRect(destinationRect, shadePaint)
            }
        }
    }

    private fun setSourceRect(bitmap: Bitmap, index: Int, right: Boolean, back: Boolean) {
        val start = index.toFloat() / NovelDoublePageCurlGeometry.STRIP_COUNT
        val end = (index + 1f) / NovelDoublePageCurlGeometry.STRIP_COUNT
        val x0 = NovelDoublePageCurlGeometry.textureX(bitmap.width.toFloat(), start, right, back)
        val x1 = NovelDoublePageCurlGeometry.textureX(bitmap.width.toFloat(), end, right, back)
        sourceRect.set(
            floor(min(x0, x1)).toInt(),
            0,
            ceil(max(x0, x1)).toInt(),
            bitmap.height,
        )
    }

    private fun drawTargetShadow(canvas: Canvas, width: Float, height: Float, right: Boolean, lift: Float) {
        if (lift <= EPSILON) return
        val spine = width / 2f
        val curlEdge = if (right) screenX.max() else screenX.min()
        val edge = if (right) max(spine, curlEdge) else min(spine, curlEdge)
        val shadowWidth = width * SHADOW_WIDTH_RATIO
        val alpha = (SHADOW_MAX_ALPHA * lift).toInt()
        val inner = (alpha * 0.55f).toInt()
        val soft = (alpha * 0.18f).toInt()
        val left = if (right) edge else edge - shadowWidth
        val end = if (right) edge + shadowWidth else edge
        val colors = if (right) {
            intArrayOf(alpha shl 24, inner shl 24, soft shl 24, Color.TRANSPARENT)
        } else {
            intArrayOf(Color.TRANSPARENT, soft shl 24, inner shl 24, alpha shl 24)
        }
        // Curve strips change this paint's alpha; reset it before using shader alpha.
        shadePaint.color = Color.BLACK
        shadePaint.shader = LinearGradient(left, 0f, end, 0f, colors, SHADOW_STOPS, Shader.TileMode.CLAMP)
        canvas.withClip(if (right) spine else 0f, if (right) width else spine, height) {
            drawRect(left, 0f, end, height, shadePaint)
        }
        shadePaint.shader = null
    }

    private fun Canvas.drawPage(bitmap: Bitmap, width: Float, height: Float) {
        destinationRect.set(0f, 0f, width, height)
        drawBitmap(bitmap, null, destinationRect, bitmapPaint)
    }

    private inline fun Canvas.withClip(left: Float, right: Float, height: Float, block: Canvas.() -> Unit) {
        val save = save()
        clipRect(left, 0f, right, height)
        block()
        restoreToCount(save)
    }

    private companion object {
        const val EPSILON = 0.001f
        const val CURVE_SHADE_ALPHA = 24f
        const val SHADOW_WIDTH_RATIO = 0.32f
        const val SHADOW_MAX_ALPHA = 96f
        val SHADOW_STOPS = floatArrayOf(0f, 0.18f, 0.55f, 1f)
    }
}
