package com.foodflow.app.navigation

/**
 * Lightweight UI summary derived from the single shared Cart state for the
 * floating bottom Cart Preview bar and Cart navigation badge.
 */
data class AppCartPreviewState(
    val itemCount: Int = 0,
    val totalPayable: Double = 0.0,
    val restaurantName: String = ""
) {
    val isVisible: Boolean
        get() = itemCount > 0
}
