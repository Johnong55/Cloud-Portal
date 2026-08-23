package com.trijohn.cloudportal

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/**
 * Gives iCloud Photos deterministic, one-photo-at-a-time paging.
 *
 * iCloud's web carousel can change its decision while a finger drifts diagonally and can carry its
 * momentum across more than one photo. Once a deliberate horizontal swipe is detected, the web
 * gesture is cancelled and translated to exactly one previous/next command. Vertical scrolling,
 * taps and multi-touch/pinch gestures continue to use WebView's normal event path.
 */
internal class StableSwipeWebView(context: Context) : WebView(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minimumPageDistance = MINIMUM_PAGE_DISTANCE_DP * resources.displayMetrics.density
    private var gestureAxis = SwipeAxis.Unresolved
    private var downX = 0f
    private var downY = 0f
    private var webGestureCancelled = false

    var nativePhotoPagingEnabled = false

    override fun performClick(): Boolean = super.performClick()

    // WebView's own onTouchEvent continues to detect taps and call its accessibility click path.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!nativePhotoPagingEnabled) {
            resetGestureAfter(event)
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureAxis = SwipeAxis.Unresolved
                webGestureCancelled = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gestureAxis != SwipeAxis.Horizontal) gestureAxis = SwipeAxis.Unrestricted
            }
        }

        if (event.pointerCount > 1 && gestureAxis != SwipeAxis.Horizontal) {
            gestureAxis = SwipeAxis.Unrestricted
        }

        if (
            gestureAxis == SwipeAxis.Unresolved &&
            (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)
        ) {
            gestureAxis = SwipeAxisResolver.resolve(
                deltaX = event.x - downX,
                deltaY = event.y - downY,
                touchSlop = touchSlop,
            )
        }

        if (gestureAxis == SwipeAxis.Horizontal) {
            if (!webGestureCancelled) {
                cancelWebGesture(event)
                webGestureCancelled = true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                PhotoSwipePagingPolicy.pageFor(
                    deltaX = event.x - downX,
                    minimumDistance = minimumPageDistance,
                )?.let(::sendPageCommand)
            }
            resetGestureAfter(event)
            return true
        }

        val adjustedEvent = when (gestureAxis) {
            SwipeAxis.Vertical -> MotionEvent.obtain(event).apply { setLocation(downX, event.y) }
            SwipeAxis.Horizontal,
            SwipeAxis.Unresolved,
            SwipeAxis.Unrestricted,
            -> null
        }

        val handled = if (adjustedEvent == null) {
            super.onTouchEvent(event)
        } else {
            try {
                super.onTouchEvent(adjustedEvent)
            } finally {
                adjustedEvent.recycle()
            }
        }

        resetGestureAfter(event)
        return handled
    }

    private fun cancelWebGesture(source: MotionEvent) {
        MotionEvent.obtain(source).also { cancelledEvent ->
            try {
                cancelledEvent.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancelledEvent)
            } finally {
                cancelledEvent.recycle()
            }
        }
    }

    private fun sendPageCommand(page: PhotoPage) {
        val keyCode = when (page) {
            PhotoPage.Previous -> KeyEvent.KEYCODE_DPAD_LEFT
            PhotoPage.Next -> KeyEvent.KEYCODE_DPAD_RIGHT
        }
        post {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun sendKeyEvent(event: KeyEvent) {
        super.dispatchKeyEvent(event)
    }

    private fun resetGestureAfter(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureAxis = SwipeAxis.Unresolved
            webGestureCancelled = false
        }
    }

    private companion object {
        const val MINIMUM_PAGE_DISTANCE_DP = 36f
    }
}

internal enum class SwipeAxis {
    Unresolved,
    Horizontal,
    Vertical,
    Unrestricted,
}

internal object SwipeAxisResolver {
    private const val DOMINANCE_RATIO = 1.2f
    private const val DECISION_DISTANCE_MULTIPLIER = 2f

    fun resolve(deltaX: Float, deltaY: Float, touchSlop: Float): SwipeAxis {
        val horizontalDistance = abs(deltaX)
        val verticalDistance = abs(deltaY)
        val distanceSquared = horizontalDistance * horizontalDistance + verticalDistance * verticalDistance
        if (distanceSquared < touchSlop * touchSlop) return SwipeAxis.Unresolved

        if (horizontalDistance >= verticalDistance * DOMINANCE_RATIO) return SwipeAxis.Horizontal
        if (verticalDistance >= horizontalDistance * DOMINANCE_RATIO) return SwipeAxis.Vertical

        val decisionDistance = touchSlop * DECISION_DISTANCE_MULTIPLIER
        if (distanceSquared < decisionDistance * decisionDistance) return SwipeAxis.Unresolved
        return if (horizontalDistance > verticalDistance) SwipeAxis.Horizontal else SwipeAxis.Vertical
    }
}

internal enum class PhotoPage {
    Previous,
    Next,
}

internal object PhotoSwipePagingPolicy {
    fun pageFor(deltaX: Float, minimumDistance: Float): PhotoPage? {
        if (abs(deltaX) < minimumDistance) return null
        return if (deltaX < 0f) PhotoPage.Next else PhotoPage.Previous
    }
}
