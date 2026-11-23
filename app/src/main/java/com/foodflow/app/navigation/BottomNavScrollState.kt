package com.foodflow.app.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.abs

/**
 * Tracks per-tab scroll position, scroll direction, and collapse/expand state for FoodFlow's
 * animated floating capsule bottom navigation bar.
 *
 * Scroll-responsive collapsing behavior:
 * - Default & at top of screen (`isAtTop == true`): **expanded** (full height with icons + labels).
 * - Scrolling down into content (`deltaY < 0`, past [scrollHysteresisPx]): **collapsed**
 *   (compact icon-only capsule with reduced height).
 * - Scrolling up toward top (`deltaY > 0`, past [scrollHysteresisPx]): **expanded** (icons + labels).
 * - Returning to the top of the screen (`isAtTop == true`): always **expanded**.
 * - Non-scrollable screens consume `0f` vertical scroll in [NestedScrollConnection.onPostScroll]
 *   and therefore remain safely **expanded**.
 * - Horizontal swipes (pager tab swipes, promo carousels, chip rows) are ignored when
 *   `|deltaX| > |deltaY|`, and tiny micro-jitter below [minScrollDeltaPx] is filtered out.
 * - Switching tabs resets any in-flight scroll accumulator and expands the active tab's nav bar
 *   so stale scroll momentum never leaks across tabs.
 * - While the soft keyboard (IME) is open, scroll deltas caused by focus/BringIntoView adjustments
 *   are ignored so the nav bar never gets stuck collapsed when the keyboard closes.
 */
@Stable
class BottomNavScrollState(
    private val scrollHysteresisPx: Float = 40f,
    private val minScrollDeltaPx: Float = 1.5f
) {
    private val atTopByTab = mutableStateMapOf<Int, Boolean>()
    private val collapsedByTab = mutableStateMapOf<Int, Boolean>()
    private val accumulatedDeltaByTab = mutableMapOf<Int, Float>()

    var activeTabIndex by mutableStateOf(0)
        private set

    var isKeyboardOpen by mutableStateOf(false)
        private set

    /** Switches the active tab and clears stale scroll momentum before expanding its navigation item. */
    fun setActiveTab(tabIndex: Int) {
        val previousTab = activeTabIndex
        activeTabIndex = tabIndex
        accumulatedDeltaByTab[previousTab] = 0f
        accumulatedDeltaByTab[tabIndex] = 0f
        // Expand cleanly when entering a tab so navigation labels are clear on arrival
        // and no stale collapsed state bleeds across tabs.
        collapsedByTab[tabIndex] = false
    }

    /** Forces a tab's navigation capsule open and resets its accumulated scroll delta. */
    fun expand(tabIndex: Int = activeTabIndex) {
        collapsedByTab[tabIndex] = false
        accumulatedDeltaByTab[tabIndex] = 0f
    }

    /** Ignores keyboard-driven scroll changes and restores expanded state when the keyboard closes at top. */
    fun onKeyboardVisibilityChanged(open: Boolean) {
        if (isKeyboardOpen == open) return
        isKeyboardOpen = open
        accumulatedDeltaByTab[activeTabIndex] = 0f
        if (!open && isTabAtTop(activeTabIndex)) {
            collapsedByTab[activeTabIndex] = false
        }
    }

    /** Returns the saved top-of-content state, treating an unseen tab as initially at top. */
    fun isTabAtTop(tabIndex: Int): Boolean = atTopByTab[tabIndex] ?: true

    /** Reports collapse only after the tab has left its top position. */
    fun isCollapsed(tabIndex: Int = activeTabIndex): Boolean {
        if (isTabAtTop(tabIndex)) return false
        return collapsedByTab[tabIndex] ?: false
    }

    /** Derives whether the tab's navigation labels should be visible. */
    fun shouldShowLabels(tabIndex: Int = activeTabIndex): Boolean = !isCollapsed(tabIndex)

    /** Saves list-boundary state and resets scroll accumulation when content returns to top. */
    fun onTabAtTopChanged(tabIndex: Int, isAtTop: Boolean) {
        atTopByTab[tabIndex] = isAtTop
        if (isAtTop) {
            collapsedByTab[tabIndex] = false
            accumulatedDeltaByTab[tabIndex] = 0f
        }
    }

    /**
     * Accumulates meaningful vertical movement per tab and changes collapse state only after the
     * hysteresis threshold is crossed. Direction reversals restart the accumulator, while top-of-list
     * and keyboard state force expansion or ignore movement to avoid jitter and IME-induced changes.
     * Compose scroll sign convention: negative deltas move deeper into content; positive deltas return upward.
     */
    fun onVerticalScrollDelta(tabIndex: Int, deltaY: Float) {
        if (isKeyboardOpen || abs(deltaY) < minScrollDeltaPx) return

        // Scrolling down away from the top transitions out of at-top state immediately
        if (deltaY < -minScrollDeltaPx && isTabAtTop(tabIndex)) {
            atTopByTab[tabIndex] = false
        }

        if (isTabAtTop(tabIndex)) {
            collapsedByTab[tabIndex] = false
            accumulatedDeltaByTab[tabIndex] = 0f
            return
        }

        val currentAcc = accumulatedDeltaByTab[tabIndex] ?: 0f
        // Reset accumulator cleanly on direction reversal so state transitions stay crisp without jitter
        val nextAcc = if ((currentAcc > 0f && deltaY < 0f) || (currentAcc < 0f && deltaY > 0f)) {
            deltaY
        } else {
            currentAcc + deltaY
        }
        accumulatedDeltaByTab[tabIndex] = nextAcc

        when {
            // Scrolling downward deeper into list -> collapse nav bar to compact icon-only mode
            nextAcc <= -scrollHysteresisPx -> {
                collapsedByTab[tabIndex] = true
                accumulatedDeltaByTab[tabIndex] = 0f
            }
            // Scrolling upward toward top -> expand nav bar to full height with labels
            nextAcc >= scrollHysteresisPx -> {
                collapsedByTab[tabIndex] = false
                accumulatedDeltaByTab[tabIndex] = 0f
            }
        }
    }

    /** Adapts user vertical scroll events into per-tab collapse state while filtering horizontal and IME motion. */
    fun createNestedScrollConnection(tabIndexProvider: () -> Int): NestedScrollConnection {
        return object : NestedScrollConnection {
            /** Handles scroll after child consumption so only real vertical list movement affects the bar. */
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (isKeyboardOpen || source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }

                val tabIndex = tabIndexProvider()

                // Ignore predominantly horizontal gestures (HorizontalPager swipes, LazyRow carousels)
                val absX = abs(consumed.x) + abs(available.x)
                val absY = abs(consumed.y) + abs(available.y)
                if (absX > absY) {
                    return Offset.Zero
                }

                // Only react to actual consumed vertical scroll so non-scrollable screens
                // and pull-to-refresh overscroll at top never accidentally collapse the bar.
                if (consumed.y != 0f) {
                    onVerticalScrollDelta(tabIndex, consumed.y)
                } else if (available.y > scrollHysteresisPx * 0.5f) {
                    // User pulled downward at the very top boundary -> guarantee expanded state
                    onTabAtTopChanged(tabIndex, true)
                }

                return Offset.Zero
            }
        }
    }
}
