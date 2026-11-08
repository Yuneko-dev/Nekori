package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import kotlin.math.max
import kotlin.math.min

class NovelPageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val meshVertices = FloatArray(NovelPageCurlGeometry.VERTEX_FLOAT_COUNT)
    private val doublePageRenderer = NovelDoublePageCurlRenderer()
    private var source: Bitmap? = null
    private var target: Bitmap? = null
    private var progress = 0f
    private var readingDirection = NovelPageCurlReadingDirection.LTR
    private var turnDirection = NovelPageCurlTurnDirection.FORWARD
    private var side = NovelPageCurlSide.FULL
    private var effect = NovelPageEffect.CURL

    init {
        visibility = INVISIBLE
    }

    fun setPages(
        source: Bitmap,
        target: Bitmap,
        readingDirection: NovelPageCurlReadingDirection,
        turnDirection: NovelPageCurlTurnDirection,
        side: NovelPageCurlSide,
        effect: NovelPageEffect,
    ) {
        this.source = source
        this.target = target
        this.readingDirection = readingDirection
        this.turnDirection = turnDirection
        this.side = side
        this.effect = effect
        progress = 0f
        visibility = VISIBLE
        invalidate()
    }

    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    fun clearPages() {
        source = null
        target = null
        visibility = INVISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sourceBitmap = source ?: return
        val targetBitmap = target ?: return
        if (width <= 0 || height <= 0) return
        when (effect) {
            NovelPageEffect.NONE -> canvas.drawPage(sourceBitmap, 0f)
            NovelPageEffect.HORIZONTAL -> drawHorizontal(canvas, sourceBitmap, targetBitmap)
            NovelPageEffect.SLIDE -> drawSlide(canvas, sourceBitmap, targetBitmap)
            NovelPageEffect.CURL -> drawPageTurn(canvas, sourceBitmap, targetBitmap)
        }
    }

    private fun drawHorizontal(canvas: Canvas, source: Bitmap, target: Bitmap) {
        val frame = NovelPageCurlGeometry.horizontalFrame(width.toFloat(), progress, curlsFromRight())
        canvas.drawPage(target, frame.targetOffsetX)
        canvas.drawPage(source, frame.sourceOffsetX)
        drawMovingShadow(canvas, frame.sourceOffsetX)
    }

    private fun drawSlide(canvas: Canvas, source: Bitmap, target: Bitmap) {
        val movesLeft = curlsFromRight()
        val frame = NovelPageCurlGeometry.slideFrame(width.toFloat(), progress, movesLeft)
        if (movesLeft) {
            canvas.drawPage(target, frame.targetOffsetX)
            canvas.drawPage(source, frame.sourceOffsetX)
            drawMovingShadow(canvas, frame.sourceOffsetX)
        } else {
            canvas.drawPage(source, frame.sourceOffsetX)
            canvas.drawPage(target, frame.targetOffsetX)
            drawMovingShadow(canvas, frame.targetOffsetX)
        }
    }

    private fun drawPageTurn(canvas: Canvas, source: Bitmap, target: Bitmap) {
        if (side != NovelPageCurlSide.FULL) {
            doublePageRenderer.draw(
                canvas = canvas,
                source = source,
                target = target,
                width = width.toFloat(),
                height = height.toFloat(),
                progress = progress,
                side = side,
            )
            return
        }
        val mesh = NovelPageCurlGeometry.createMesh(
            width = width.toFloat(),
            height = height.toFloat(),
            progress = progress,
            readingDirection = readingDirection,
            turnDirection = turnDirection,
            side = side,
            vertices = meshVertices,
        )
        val unfoldsPrevious = turnDirection == NovelPageCurlTurnDirection.BACKWARD
        val topPage = if (unfoldsPrevious) target else source
        canvas.drawPage(if (unfoldsPrevious) source else target, 0f)

        val curlBounds = curlBounds(mesh)
        if (!curlBounds.isEmpty) {
            canvas.withRectClip(curlBounds) {
                drawBitmapMesh(
                    topPage,
                    NovelPageCurlGeometry.MESH_WIDTH,
                    NovelPageCurlGeometry.MESH_HEIGHT,
                    mesh.frontVertices,
                    0,
                    null,
                    0,
                    bitmapPaint,
                )
            }
        }
        drawSinglePageShadow(canvas, mesh)
    }

    private fun curlBounds(mesh: NovelPageCurlMesh): RectF {
        val left: Float
        val right: Float
        if (turnsOutwardRight()) {
            left = mesh.spineX
            right = max(mesh.spineX, mesh.edgeX)
        } else {
            left = min(mesh.spineX, mesh.edgeX)
            right = mesh.spineX
        }
        return RectF(left, 0f, right, height.toFloat())
    }

    private fun drawSinglePageShadow(canvas: Canvas, mesh: NovelPageCurlMesh) {
        if (mesh.amount <= 0f || mesh.amount >= 1f) return
        val outwardRight = turnsOutwardRight()
        val lift = NovelPageCurlGeometry.shadowOpacity(mesh.amount)
        val shadowWidth = min(width * CURL_SHADOW_WIDTH_RATIO, CURL_SHADOW_MAX_WIDTH * resources.displayMetrics.density)
        val alpha = (CURL_SHADOW_MAX_ALPHA * lift).toInt()
        val innerAlpha = (alpha * CURL_SHADOW_INNER_ALPHA_RATIO).toInt()
        val softAlpha = (alpha * CURL_SHADOW_SOFT_ALPHA_RATIO).toInt()
        val edge = if (outwardRight) max(mesh.spineX, mesh.edgeX) else min(mesh.spineX, mesh.edgeX)
        val left = if (outwardRight) edge else edge - shadowWidth
        val right = if (outwardRight) edge + shadowWidth else edge
        val colors = if (outwardRight) {
            intArrayOf(alpha shl 24, innerAlpha shl 24, softAlpha shl 24, TRANSPARENT)
        } else {
            intArrayOf(TRANSPARENT, softAlpha shl 24, innerAlpha shl 24, alpha shl 24)
        }
        shadePaint.shader = LinearGradient(
            left,
            0f,
            right,
            0f,
            colors,
            CURL_SHADOW_STOPS,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, 0f, right, height.toFloat(), shadePaint)
        shadePaint.shader = null
    }

    private fun drawMovingShadow(canvas: Canvas, offset: Float) {
        val edge = NovelPageCurlGeometry.movingShadowEdge(width.toFloat(), offset, progress) ?: return
        val shadowWidth = max(1f, width * SHADOW_WIDTH_RATIO)
        val alpha = (32f * NovelPageCurlGeometry.shadowOpacity(progress)).toInt()
        val toRight = offset < 0f
        val left = if (toRight) edge else edge - shadowWidth
        val right = if (toRight) edge + shadowWidth else edge
        shadePaint.shader = LinearGradient(
            left,
            0f,
            right,
            0f,
            if (toRight) intArrayOf(alpha shl 24, TRANSPARENT) else intArrayOf(TRANSPARENT, alpha shl 24),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, 0f, right, height.toFloat(), shadePaint)
        shadePaint.shader = null
    }

    private inline fun Canvas.withRectClip(rect: RectF, block: Canvas.() -> Unit) {
        val save = save()
        clipRect(rect)
        block()
        restoreToCount(save)
    }

    private fun Canvas.drawPage(bitmap: Bitmap, offsetX: Float) {
        bounds.set(offsetX, 0f, offsetX + width, height.toFloat())
        drawBitmap(bitmap, null, bounds, bitmapPaint)
    }

    private fun curlsFromRight() = NovelPageCurlGeometry.curlsFromRight(readingDirection, turnDirection)

    private fun turnsOutwardRight() = when (side) {
        NovelPageCurlSide.RIGHT -> true
        NovelPageCurlSide.LEFT -> false
        NovelPageCurlSide.FULL -> readingDirection == NovelPageCurlReadingDirection.LTR
    }

    private companion object {
        const val SHADOW_WIDTH_RATIO = 0.035f
        const val CURL_SHADOW_WIDTH_RATIO = 0.32f
        const val CURL_SHADOW_MAX_WIDTH = 180f
        const val CURL_SHADOW_MAX_ALPHA = 96
        const val CURL_SHADOW_INNER_ALPHA_RATIO = 0.55f
        const val CURL_SHADOW_SOFT_ALPHA_RATIO = 0.18f
        const val TRANSPARENT = 0x00000000
        val CURL_SHADOW_STOPS = floatArrayOf(0f, 0.18f, 0.55f, 1f)
    }
}
