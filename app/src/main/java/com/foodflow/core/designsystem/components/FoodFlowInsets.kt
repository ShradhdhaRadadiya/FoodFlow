package com.foodflow.core.designsystem.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized edge-to-edge WindowInsets helpers for FoodFlow.
 *
 * Ensures consistent status-bar, navigation-bar (gesture & 3-button), FAB,
 * and IME/keyboard clearance across all secondary screens, bottom sheets,
 * and sticky action bars without introducing double insets.
 */
/** Adds navigation-bar clearance to a bottom-aligned navigation surface. */
fun Modifier.foodFlowBottomBarInsets(): Modifier = this.navigationBarsPadding()

/** Adds navigation-bar clearance to a floating action button. */
fun Modifier.foodFlowFabInsets(): Modifier = this.navigationBarsPadding()

/** Applies the union of IME and navigation-bar insets so bottom content avoids both. */
@Composable
fun Modifier.foodFlowKeyboardAndNavInsets(): Modifier =
    this.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))

/** Adds status-bar clearance to content drawn edge to edge. */
fun Modifier.foodFlowStatusBarInsets(): Modifier = this.statusBarsPadding()

/**
 * Returns bottom padding that safely clears the Android navigation bar plus [extra] spacing.
 * Pass [extra] = 88.dp when a screen displays a bottom-end FloatingActionButton.
 */
@Composable
fun foodFlowNavBarBottomDp(extra: Dp = 16.dp): Dp {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return navBottom + extra
}

/**
 * Builds consistent LazyColumn/LazyRow content padding that respects the system navigation bar.
 */
@Composable
fun foodFlowScreenListPadding(
    horizontal: Dp = 16.dp,
    top: Dp = 12.dp,
    bottomExtra: Dp = 24.dp,
    includeNavBarInset: Boolean = true
): PaddingValues {
    val bottom = if (includeNavBarInset) {
        foodFlowNavBarBottomDp(extra = bottomExtra)
    } else {
        bottomExtra
    }
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = top,
        bottom = bottom
    )
}
