package com.foodflow.feature.voice

/**
 * Pure geometry helper for the draggable floating Voice Assistant trigger button.
 * Clamps drag coordinates strictly inside visible screen bounds and snaps horizontally
 * to the nearest screen edge upon release.
 */
data class FloatingButtonBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
)

data class FloatingButtonPosition(
    val x: Float,
    val y: Float
)

object FloatingAssistantPositionMath {

    /** Keeps the floating button coordinates inside the visible screen bounds. */
    fun clamp(
        x: Float,
        y: Float,
        bounds: FloatingButtonBounds
    ): FloatingButtonPosition {
        val safeMaxX = bounds.maxX.coerceAtLeast(bounds.minX)
        val safeMaxY = bounds.maxY.coerceAtLeast(bounds.minY)
        return FloatingButtonPosition(
            x = x.coerceIn(bounds.minX, safeMaxX),
            y = y.coerceIn(bounds.minY, safeMaxY)
        )
    }

    /** Moves the button to the nearest screen edge after dragging. */
    fun snapToNearestHorizontalEdge(
        x: Float,
        y: Float,
        bounds: FloatingButtonBounds
    ): FloatingButtonPosition {
        val clamped = clamp(x, y, bounds)
        val midpoint = (bounds.minX + bounds.maxX.coerceAtLeast(bounds.minX)) / 2f
        val snappedX = if (clamped.x < midpoint) bounds.minX else bounds.maxX.coerceAtLeast(bounds.minX)
        return FloatingButtonPosition(x = snappedX, y = clamped.y)
    }

    /** Returns whether the button position is on or right of the screen midpoint. */
    fun isSnappedRight(x: Float, bounds: FloatingButtonBounds): Boolean {
        val midpoint = (bounds.minX + bounds.maxX.coerceAtLeast(bounds.minX)) / 2f
        return x >= midpoint
    }

    /** Converts the button's vertical position into a 0-to-1 screen fraction. */
    fun verticalFraction(y: Float, bounds: FloatingButtonBounds): Float {
        val span = (bounds.maxY - bounds.minY).coerceAtLeast(1f)
        return ((y - bounds.minY) / span).coerceIn(0f, 1f)
    }

    /** Restores screen coordinates from an edge choice and saved vertical fraction. */
    fun fromNormalized(
        snappedRight: Boolean,
        yFraction: Float,
        bounds: FloatingButtonBounds
    ): FloatingButtonPosition {
        val safeMaxX = bounds.maxX.coerceAtLeast(bounds.minX)
        val safeMaxY = bounds.maxY.coerceAtLeast(bounds.minY)
        val x = if (snappedRight) safeMaxX else bounds.minX
        val y = bounds.minY + (safeMaxY - bounds.minY) * yFraction.coerceIn(0f, 1f)
        return FloatingButtonPosition(x = x, y = y)
    }
}
