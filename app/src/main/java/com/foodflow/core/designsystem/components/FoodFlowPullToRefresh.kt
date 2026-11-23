package com.foodflow.core.designsystem.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Wraps scrollable content with Material 3 pull-to-refresh progress and refresh callbacks.
 * Uses FoodFlow surface/primary colors; apply scaffold inner padding to [modifier] so the indicator
 * sits below the top bar and clears the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodFlowPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val pullToRefreshState = rememberPullToRefreshState()
    val taggedModifier = if (testTag != null) {
        modifier.testTag(testTag)
    } else {
        modifier
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        modifier = taggedModifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                containerColor = MaterialTheme.colorScheme.surface,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        },
        content = content
    )
}
