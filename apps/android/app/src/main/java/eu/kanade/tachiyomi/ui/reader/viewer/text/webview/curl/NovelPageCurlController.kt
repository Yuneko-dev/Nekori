package eu.kanade.tachiyomi.ui.reader.viewer.text.webview.curl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import eu.kanade.tachiyomi.ui.reader.setting.NovelPageEffect
import kotlin.math.abs

internal fun finishPageTurn(
    commit: Boolean,
    clearOverlay: () -> Unit,
    commitPage: ((() -> Unit) -> Unit),
    rollbackPage: ((() -> Unit) -> Unit),
) {
    if (commit) {
        commitPage(clearOverlay)
    } else {
        rollbackPage(clearOverlay)
    }
}

internal fun finishAfterVisualState(
    awaitVisualState: ((() -> Unit) -> Unit),
    postFrame: ((() -> Unit) -> Unit),
    finish: () -> Unit,
) = awaitVisualState { postFrame(finish) }

class NovelPageCurlController(
    private val curlView: NovelPageCurlView,
    private val sourceView: View,
    private val requestTarget: (Int, (Boolean) -> Unit) -> Unit,
    private val onCommit: (NovelPageCurlTurnDirection, () -> Unit) -> Unit,
    private val onRollback: ((() -> Unit) -> Unit),
    private val onFallback: (NovelPageCurlTurnDirection) -> Unit,
) {
    enum class State { IDLE, PREPARING, DRAGGING, SETTLING }

    var state = State.IDLE
        private set

    private var sourceBitmap: Bitmap? = null
    private var targetBitmap: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var startX = 0f
    private var dragDistance = 0f
    private var progressWidth = 0f
    private var commitWidth = 0f
    private var progress = 0f
    private var curlsFromRight = true
    private var turnDirection = NovelPageCurlTurnDirection.FORWARD
    private val touchSlop = ViewConfiguration.get(curlView.context).scaledTouchSlop
    private var trackingTouch = false
    private var claimedTouch = false
    private var downY = 0f
    private var lastTouchX = 0f
    private var velocityTracker: VelocityTracker? = null
    private var pendingVelocity: Float? = null
    private var cancelPending = false
    private var consumeUntilUp = false

    fun startTimed(
        delta: Int,
        readingDirection: NovelPageCurlReadingDirection,
        doubleSpread: Boolean,
        effect: NovelPageEffect,
    ) {
        val curlsFromRight = NovelPageCurlGeometry.curlsFromRight(readingDirection, turnFor(delta))
        val touchX = if (curlsFromRight) sourceView.width.toFloat() else 0f
        beginTurn(delta, touchX, readingDirection, doubleSpread, effect) {
            if (state == State.DRAGGING) settle(commit = true)
        }
    }

    fun onTouchEvent(
        event: MotionEvent,
        canStart: Boolean,
        readingDirection: NovelPageCurlReadingDirection,
        doubleSpread: Boolean,
        effect: NovelPageEffect,
    ): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            consumeUntilUp = false
            resetTouch()
            trackingTouch = canStart && state == State.IDLE
            if (trackingTouch) {
                startX = event.x
                lastTouchX = event.x
                downY = event.y
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            false
        }
        MotionEvent.ACTION_MOVE -> if (consumeUntilUp) {
            true
        } else {
            handleMove(event, canStart, readingDirection, doubleSpread, effect)
        }
        MotionEvent.ACTION_UP -> if (consumeUntilUp) consumeFallbackTouch() else finishTouch(event)
        MotionEvent.ACTION_CANCEL -> if (consumeUntilUp) consumeFallbackTouch() else cancelTouch()
        else -> claimedTouch
    }

    private fun handleMove(
        event: MotionEvent,
        canStart: Boolean,
        readingDirection: NovelPageCurlReadingDirection,
        doubleSpread: Boolean,
        effect: NovelPageEffect,
    ): Boolean {
        if (!trackingTouch) return false
        velocityTracker?.addMovement(event)
        lastTouchX = event.x
        if (!claimedTouch) {
            val deltaX = event.x - startX
            if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) {
                resetTouch()
                return false
            }
            if (abs(deltaX) <= touchSlop || abs(deltaX) <= abs(event.y - downY)) return false
            if (!canStart) {
                resetTouch()
                return false
            }
            val delta = NovelPageCurlGeometry.logicalDelta(deltaX, readingDirection)
            if (!beginTurn(delta, startX, readingDirection, doubleSpread, effect) { completeTouchPreparation() }) {
                // prepare() already performed the one-unit fallback.
                return true
            }
            claimedTouch = true
            cancelSourceTouch(event)
        }
        if (state == State.DRAGGING) update(lastTouchX)
        return true
    }

    private fun finishTouch(event: MotionEvent): Boolean {
        if (!claimedTouch) {
            resetTouch()
            return false
        }
        velocityTracker?.run {
            addMovement(event)
            computeCurrentVelocity(1_000)
            pendingVelocity = xVelocity
        }
        lastTouchX = event.x
        if (state == State.DRAGGING) completeTouchPreparation()
        clearTouchTracker()
        return true
    }

    private fun cancelTouch(): Boolean {
        val consumed = claimedTouch
        if (consumed) cancel() else resetTouch()
        return consumed
    }

    private fun completeTouchPreparation() {
        if (state != State.DRAGGING) return
        update(lastTouchX)
        pendingVelocity?.let {
            pendingVelocity = null
            finish(it)
        }
    }

    private fun beginTurn(
        delta: Int,
        touchX: Float,
        readingDirection: NovelPageCurlReadingDirection,
        doubleSpread: Boolean,
        effect: NovelPageEffect,
        onReady: () -> Unit,
    ): Boolean {
        val turn = turnFor(delta)
        val curlsFromRight = NovelPageCurlGeometry.curlsFromRight(readingDirection, turn)
        val side = when {
            effect != NovelPageEffect.CURL || !doubleSpread -> NovelPageCurlSide.FULL
            curlsFromRight -> NovelPageCurlSide.RIGHT
            else -> NovelPageCurlSide.LEFT
        }
        if (!prepare(sourceView, touchX, readingDirection, turn, side, effect)) return false
        requestTarget(if (delta < 0) -1 else 1) { moved ->
            if (state != State.PREPARING) {
                if (moved) onRollback {}
                return@requestTarget
            }
            if (cancelPending || !moved) {
                abortPreparation(rollback = moved)
                return@requestTarget
            }
            sourceView.postOnAnimation {
                sourceView.postOnAnimation {
                    if (cancelPending) {
                        abortPreparation(rollback = true)
                    } else if (finishPreparation(sourceView)) {
                        onReady()
                    }
                }
            }
        }
        return true
    }

    private fun abortPreparation(rollback: Boolean) {
        consumeUntilUp = trackingTouch || claimedTouch
        cancelPending = false
        resetTouch()
        if (rollback) {
            onRollback(::clearTurn)
        } else {
            clearTurn()
        }
    }

    private fun turnFor(delta: Int) = if (delta < 0) {
        NovelPageCurlTurnDirection.BACKWARD
    } else {
        NovelPageCurlTurnDirection.FORWARD
    }

    private fun cancelSourceTouch(event: MotionEvent) {
        MotionEvent.obtain(event).also {
            it.action = MotionEvent.ACTION_CANCEL
            sourceView.onTouchEvent(it)
            it.recycle()
        }
    }

    fun prepare(
        source: View,
        touchX: Float,
        readingDirection: NovelPageCurlReadingDirection,
        turnDirection: NovelPageCurlTurnDirection,
        side: NovelPageCurlSide = NovelPageCurlSide.FULL,
        effect: NovelPageEffect = NovelPageEffect.CURL,
    ): Boolean {
        if (state != State.IDLE) return false
        if (!hasDrawableBounds(source)) {
            onFallback(turnDirection)
            return false
        }
        state = State.PREPARING
        this.turnDirection = turnDirection
        return try {
            val size = NovelPageCurlGeometry.bitmapSize(curlView.width, curlView.height)
            sourceBitmap = obtainBitmap(sourceBitmap, size)
            targetBitmap = obtainBitmap(targetBitmap, size)
            capture(source, requireNotNull(sourceBitmap))
            curlsFromRight = NovelPageCurlGeometry.curlsFromRight(readingDirection, turnDirection)
            startX = touchX
            dragDistance = 0f
            progress = 0f
            val gestureWidths = NovelPageCurlGeometry.gestureWidths(
                viewportWidth = curlView.width.toFloat(),
                doubleSpread = side != NovelPageCurlSide.FULL,
                curlEffect = effect == NovelPageEffect.CURL,
            )
            progressWidth = gestureWidths.progress
            commitWidth = gestureWidths.commit
            pendingReadingDirection = readingDirection
            pendingSide = side
            pendingEffect = effect
            curlView.setPages(
                requireNotNull(sourceBitmap),
                requireNotNull(sourceBitmap),
                readingDirection,
                turnDirection,
                side,
                effect,
            )
            true
        } catch (_: RuntimeException) {
            fallback(turnDirection)
            false
        } catch (_: OutOfMemoryError) {
            fallback(turnDirection)
            false
        }
    }

    fun finishPreparation(target: View): Boolean {
        if (state != State.PREPARING) return false
        if (!hasDrawableBounds(target)) {
            fallback(turnDirection)
            return false
        }
        return try {
            capture(target, requireNotNull(targetBitmap))
            curlView.setPages(
                requireNotNull(sourceBitmap),
                requireNotNull(targetBitmap),
                pendingReadingDirection,
                turnDirection,
                pendingSide,
                pendingEffect,
            )
            state = State.DRAGGING
            true
        } catch (_: RuntimeException) {
            fallback(turnDirection)
            false
        } catch (_: OutOfMemoryError) {
            fallback(turnDirection)
            false
        }
    }

    fun update(touchX: Float) {
        if (state != State.DRAGGING) return
        dragDistance = NovelPageCurlGeometry.directedDrag(startX, touchX, curlsFromRight)
            .coerceAtMost(progressWidth)
        progress = dragDistance / progressWidth
        curlView.setProgress(progress)
    }

    fun finish(velocityX: Float) {
        if (state != State.DRAGGING) return
        val commit = NovelPageCurlGeometry.shouldCommit(
            dragDistance = dragDistance,
            leafWidth = commitWidth,
            directedVelocity = NovelPageCurlGeometry.directedVelocity(velocityX, curlsFromRight),
        )
        val velocity = NovelPageCurlGeometry.directedVelocity(velocityX, curlsFromRight)
        settle(commit, if (commit) velocity else -velocity)
    }

    fun cancel() {
        if (state == State.PREPARING) {
            cancelPending = true
            resetTouch()
            return
        }
        if (state != State.DRAGGING && state != State.SETTLING) return
        stopAnimator()
        settle(commit = false)
    }

    fun destroy() {
        val shouldRollback = state != State.IDLE
        val awaitingTarget = state == State.PREPARING
        stopAnimator()
        curlView.clearPages()
        sourceBitmap?.recycle()
        targetBitmap?.recycle()
        sourceBitmap = null
        targetBitmap = null
        state = if (awaitingTarget) State.PREPARING else State.IDLE
        cancelPending = awaitingTarget
        resetTouch()
        if (shouldRollback) onRollback {}
    }

    private fun settle(commit: Boolean, velocity: Float = 0f) {
        state = State.SETTLING
        val destination = if (commit) 1f else 0f
        val animation = ValueAnimator.ofFloat(progress, destination).apply {
            duration = NovelPageCurlGeometry.settleDuration(progress, commit, velocity, progressWidth)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                curlView.setProgress(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    finishPageTurn(
                        commit = commit,
                        clearOverlay = ::clearTurn,
                        commitPage = { onCommitted -> onCommit(turnDirection, onCommitted) },
                        rollbackPage = onRollback,
                    )
                }
            })
        }
        animator = animation
        animation.start()
    }

    private var pendingReadingDirection = NovelPageCurlReadingDirection.LTR
    private var pendingSide = NovelPageCurlSide.FULL
    private var pendingEffect = NovelPageEffect.CURL

    private fun clearTurn() {
        curlView.clearPages()
        state = State.IDLE
        resetTouch()
    }

    private fun hasDrawableBounds(source: View): Boolean =
        curlView.width > 0 && curlView.height > 0 && source.width > 0 && source.height > 0

    private fun capture(view: View, bitmap: Bitmap) {
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width.toFloat() / view.width, bitmap.height.toFloat() / view.height)
        view.draw(canvas)
    }

    private fun obtainBitmap(current: Bitmap?, size: NovelPageCurlBitmapSize): Bitmap {
        if (current != null && !current.isRecycled && current.width == size.width && current.height == size.height) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    }

    private fun fallback(direction: NovelPageCurlTurnDirection) {
        consumeUntilUp = trackingTouch || claimedTouch
        curlView.clearPages()
        progress = 0f
        state = State.IDLE
        cancelPending = false
        resetTouch()
        onFallback(direction)
    }

    private fun consumeFallbackTouch(): Boolean {
        consumeUntilUp = false
        resetTouch()
        return true
    }

    private fun resetTouch() {
        clearTouchTracker()
        pendingVelocity = null
    }

    private fun clearTouchTracker() {
        trackingTouch = false
        claimedTouch = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun stopAnimator() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }
}
