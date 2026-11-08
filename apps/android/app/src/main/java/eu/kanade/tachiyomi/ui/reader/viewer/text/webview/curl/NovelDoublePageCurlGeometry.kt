package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal data class NovelDoublePageCurlFrame(
    val screenX: FloatArray,
    val backFacing: BooleanArray,
    val curveShade: FloatArray,
    val lift: Float,
)

/** Vertical cylinder: source outer leaf on the front, target inner leaf on the back. */
internal object NovelDoublePageCurlGeometry {
    const val STRIP_COUNT = 48

    fun create(width: Float, progress: Float, turnsRightLeaf: Boolean): NovelDoublePageCurlFrame {
        val screenX = FloatArray(STRIP_COUNT + 1)
        val backFacing = BooleanArray(STRIP_COUNT)
        val curveShade = FloatArray(STRIP_COUNT)
        val lift = fill(width, progress, turnsRightLeaf, screenX, backFacing, curveShade)
        return NovelDoublePageCurlFrame(screenX, backFacing, curveShade, lift)
    }

    fun fill(
        width: Float,
        progress: Float,
        turnsRightLeaf: Boolean,
        screenX: FloatArray,
        backFacing: BooleanArray,
        curveShade: FloatArray,
    ): Float {
        require(width > 0f)
        require(screenX.size == STRIP_COUNT + 1)
        require(backFacing.size == STRIP_COUNT && curveShade.size == STRIP_COUNT)
        val amount = progress.coerceIn(0f, 1f)
        val leafWidth = width / 2f
        val fold = leafWidth * (1f - amount)
        val halfArc = min(BASE_HALF_ARC * leafWidth, min(fold, leafWidth - fold))
        for (index in 0..STRIP_COUNT) {
            val materialX = leafWidth * index / STRIP_COUNT
            val projected = project(materialX, leafWidth, fold, halfArc)
            screenX[index] = if (turnsRightLeaf) leafWidth + projected else leafWidth - projected
        }
        for (index in 0 until STRIP_COUNT) {
            val materialMid = leafWidth * (index + 0.5f) / STRIP_COUNT
            val phase = curlPhase(materialMid, fold, halfArc)
            backFacing[index] = phase > HALF_TURN
            curveShade[index] = if (phase in 0f..FULL_TURN) sin(phase) else 0f
        }
        return sin(PI.toFloat() * amount).coerceAtLeast(0f)
    }

    fun textureX(bitmapWidth: Float, materialFraction: Float, turnsRightLeaf: Boolean, back: Boolean): Float {
        val half = bitmapWidth / 2f
        val outward = materialFraction.coerceIn(0f, 1f)
        val movesRight = turnsRightLeaf != back
        return if (movesRight) half * (1f + outward) else half * (1f - outward)
    }

    private fun project(materialX: Float, leafWidth: Float, fold: Float, halfArc: Float): Float {
        if (halfArc <= EPSILON) return if (fold > leafWidth / 2f) materialX else -materialX
        val arcStart = fold - halfArc
        val arcEnd = fold + halfArc
        return when {
            materialX < arcStart -> materialX
            materialX > arcEnd -> 2f * fold - materialX
            else -> {
                val phase = (materialX - arcStart) / (2f * halfArc) * FULL_TURN
                arcStart + 2f * halfArc / FULL_TURN * sin(phase)
            }
        }
    }

    private fun curlPhase(materialX: Float, fold: Float, halfArc: Float): Float = when {
        halfArc <= EPSILON -> if (materialX <= fold) -1f else FULL_TURN + 1f
        materialX < fold - halfArc -> -1f
        materialX > fold + halfArc -> FULL_TURN + 1f
        else -> (materialX - fold + halfArc) / (2f * halfArc) * FULL_TURN
    }

    private const val BASE_HALF_ARC = 0.14f
    private const val EPSILON = 0.001f
    private val HALF_TURN = PI.toFloat() / 2f
    private val FULL_TURN = PI.toFloat()
}
