package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.roundToInt

/**
 * TouchEventHandler - Ported from ScrcpyForAndroid.
 * Maps touch coordinates from view space to device screen space.
 * Accounts for content bounds (letterboxing/pillarboxing).
 */
class TouchEventHandler(
    private var sessionWidth: Int,
    private var sessionHeight: Int,
    @Volatile var touchAreaWidth: Int,
    @Volatile var touchAreaHeight: Int,
    private val onInjectTouch: (
        action: Int, pointerId: Long, x: Int, y: Int,
        pressure: Float, actionButton: Int, buttons: Int,
    ) -> Unit,
    private val onBackOrScreenOn: (action: Int) -> Unit,
) {
    companion object {
        private const val TAG = "TouchEventHandler"
        private const val POINTER_ID_MOUSE = -1L
    }

    private object UiMotionActions {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }

    private data class ContentBounds(
        val width: Float,
        val height: Float,
        val left: Float,
        val top: Float,
    )

    private val activePointerIds = LinkedHashSet<Int>()
    private val activePointerPositions = LinkedHashMap<Int, Pair<Float, Float>>()

    private val eventPointerIds = HashSet<Int>(10)
    private val eventPositions = HashMap<Int, Pair<Float, Float>>(10)
    private val eventPressures = HashMap<Int, Float>(10)
    private val justPressedPointerIds = HashSet<Int>(10)

    fun updateDimensions(width: Int, height: Int) {
        touchAreaWidth = width
        touchAreaHeight = height
    }

    fun updateSessionDimensions(width: Int, height: Int) {
        sessionWidth = width
        sessionHeight = height
    }

    private fun calculateContentBounds(): ContentBounds {
        val sessionAspect = if (sessionHeight == 0) 16f / 9f
            else sessionWidth.toFloat() / sessionHeight.toFloat()
        val containerWidth = touchAreaWidth.toFloat()
        val containerHeight = touchAreaHeight.toFloat()
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return ContentBounds(containerWidth, containerHeight, 0f, 0f)
        }
        val containerAspect = containerWidth / containerHeight

        val contentWidth: Float
        val contentHeight: Float
        if (sessionAspect > containerAspect) {
            contentWidth = containerWidth
            contentHeight = containerWidth / sessionAspect
        } else {
            contentHeight = containerHeight
            contentWidth = containerHeight * sessionAspect
        }
        val contentLeft = (containerWidth - contentWidth) / 2f
        val contentTop = (containerHeight - contentHeight) / 2f

        return ContentBounds(contentWidth, contentHeight, contentLeft, contentTop)
    }

    /**
     * Map raw touch coordinates to device screen coordinates.
     * Accounts for content bounds (letterboxing/pillarboxing offset).
     */
    private fun mapToDevice(rawX: Float, rawY: Float, bounds: ContentBounds): Pair<Int, Int> {
        val sw = sessionWidth.takeIf { it > 0 } ?: return 0 to 0
        val sh = sessionHeight.takeIf { it > 0 } ?: return 0 to 0

        val normalizedX = ((rawX - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val normalizedY = ((rawY - bounds.top) / bounds.height).coerceIn(0f, 1f)
        val x = (normalizedX * (sw - 1).coerceAtLeast(0)).roundToInt()
            .coerceIn(0, (sw - 1).coerceAtLeast(0))
        val y = (normalizedY * (sh - 1).coerceAtLeast(0)).roundToInt()
            .coerceIn(0, (sh - 1).coerceAtLeast(0))
        return x to y
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val vw = touchAreaWidth
        val vh = touchAreaHeight
        if (vw <= 0 || vh <= 0) return true

        val bounds = calculateContentBounds()

        if (isMouseLikeEvent(event)) {
            return handleMouseEvent(event, bounds)
        }

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return handleCancelAction(bounds)
        }

        extractEventData(event)
        handleDisappearedPointers(bounds)

        val endedPointerId = getEndedPointerId(event)
        handlePointerDown(event, endedPointerId, bounds)
        handlePointerMove(event, endedPointerId, bounds)
        handlePointerUp(endedPointerId, bounds)

        return true
    }

    private fun isMouseLikeEvent(event: MotionEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun handleMouseEvent(event: MotionEvent, bounds: ContentBounds): Boolean {
        val (x, y) = mapToDevice(event.getX(0), event.getY(0), bounds)
        val pressure = event.getPressure(0).coerceIn(0f, 1f)
        val buttons = event.buttonState
        val actionButton = event.actionButton

        val isHoverMotion = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> true
            MotionEvent.ACTION_MOVE -> buttons == 0
            else -> false
        }
        if (isHoverMotion) return true

        if (actionButton == MotionEvent.BUTTON_SECONDARY || buttons and MotionEvent.BUTTON_SECONDARY != 0) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> onBackOrScreenOn(0)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> onBackOrScreenOn(1)
            }
            return true
        }

        val injectAction = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_HOVER_ENTER -> MotionEvent.ACTION_HOVER_ENTER
            MotionEvent.ACTION_HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE
            MotionEvent.ACTION_HOVER_EXIT -> MotionEvent.ACTION_HOVER_EXIT
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return true
            else -> return true
        }
        onInjectTouch(injectAction, POINTER_ID_MOUSE, x, y, pressure, actionButton, buttons)
        return true
    }

    private fun releasePointer(pointerId: Int, bounds: ContentBounds) {
        if (!activePointerIds.contains(pointerId)) return
        val pos = activePointerPositions[pointerId] ?: (0f to 0f)
        val (x, y) = mapToDevice(pos.first, pos.second, bounds)
        onInjectTouch(UiMotionActions.UP, pointerId.toLong(), x, y, 0f, 0, 0)
        activePointerIds.remove(pointerId)
        activePointerPositions.remove(pointerId)
    }

    private fun handleCancelAction(bounds: ContentBounds): Boolean {
        val toCancel = activePointerIds.toList()
        for (pointerId in toCancel) {
            releasePointer(pointerId, bounds)
        }
        return true
    }

    private fun extractEventData(event: MotionEvent) {
        eventPointerIds.clear()
        eventPositions.clear()
        eventPressures.clear()
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            eventPointerIds += pointerId
            eventPositions[pointerId] = event.getX(i) to event.getY(i)
            eventPressures[pointerId] = event.getPressure(i).coerceIn(0f, 1f)
        }
    }

    private fun handleDisappearedPointers(bounds: ContentBounds) {
        val disappearedPointers = activePointerIds.filter { it !in eventPointerIds }
        for (pointerId in disappearedPointers) {
            releasePointer(pointerId, bounds)
        }
    }

    private fun getEndedPointerId(event: MotionEvent): Int? {
        return when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.getPointerId(event.actionIndex)
            else -> null
        }
    }

    private fun handlePointerDown(event: MotionEvent, endedPointerId: Int?, bounds: ContentBounds) {
        justPressedPointerIds.clear()
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            if (pointerId == endedPointerId) continue
            val raw = eventPositions[pointerId] ?: continue
            val pressure = eventPressures[pointerId] ?: 0f
            if (!activePointerIds.contains(pointerId)) {
                val (x, y) = mapToDevice(raw.first, raw.second, bounds)
                activePointerIds.add(pointerId)
                activePointerPositions[pointerId] = raw
                justPressedPointerIds.add(pointerId)
                onInjectTouch(UiMotionActions.DOWN, pointerId.toLong(), x, y, pressure, 0, 0)
            }
        }
    }

    private fun handlePointerMove(event: MotionEvent, endedPointerId: Int?, bounds: ContentBounds) {
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            if (!activePointerIds.contains(pointerId)) continue
            if (pointerId == endedPointerId) continue
            if (pointerId in justPressedPointerIds) continue
            val raw = eventPositions[pointerId] ?: continue
            val pressure = eventPressures[pointerId] ?: 0f
            activePointerPositions[pointerId] = raw
            val (x, y) = mapToDevice(raw.first, raw.second, bounds)
            onInjectTouch(UiMotionActions.MOVE, pointerId.toLong(), x, y, pressure, 0, 0)
        }
    }

    private fun handlePointerUp(endedPointerId: Int?, bounds: ContentBounds) {
        if (endedPointerId != null) {
            val endPos = eventPositions[endedPointerId]
            if (endPos != null) {
                activePointerPositions[endedPointerId] = endPos
            }
            releasePointer(endedPointerId, bounds)
        }
    }
}
