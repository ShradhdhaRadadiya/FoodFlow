package com.foodflow.feature.home

import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.FoodCategory
import com.foodflow.domain.model.Restaurant

internal data class QuadrupleHomeFeed(
    val categories: List<FoodCategory>,
    val restaurants: List<Restaurant>,
    val favoriteCount: Int,
    val address: DeliveryAddress?
)
