package com.foodflow.domain.model

data class PageRequest(
    val pageIndex: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    init {
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }
    }

    val offset: Int
        get() = pageIndex * pageSize

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 6
        const val DEFAULT_MENU_PAGE_SIZE: Int = 8
    }
}

data class PagedResult<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageSize: Int,
    val totalCount: Int,
    val hasNextPage: Boolean
)

data class RestaurantPageFilter(
    val category: String? = null,
    val sortBy: RestaurantSortOption = RestaurantSortOption.RELEVANCE,
    val pureVegOnly: Boolean = false,
    val openOnly: Boolean = false
)

data class MenuPageFilter(
    val vegOnly: Boolean = false,
    val searchQuery: String = ""
)

sealed interface PaginationLoadState {
    data object Idle : PaginationLoadState
    data object LoadingInitial : PaginationLoadState
    data object LoadingMore : PaginationLoadState
    data class Error(val message: String, val isInitialLoad: Boolean) : PaginationLoadState
    data object EndReached : PaginationLoadState
}
