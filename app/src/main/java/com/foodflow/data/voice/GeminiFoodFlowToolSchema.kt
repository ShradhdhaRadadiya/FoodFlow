package com.foodflow.data.voice

import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceToolCallInvocation
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Isolated Gemini Live protocol schema & DTO-to-Domain tool mapper.
 * Declares strictly the 9 validated FoodFlow actions so Gemini cannot invoke arbitrary operations.
 */
object GeminiFoodFlowToolSchema {

    const val TOOL_SEARCH_RESTAURANTS = "search_restaurants"
    const val TOOL_SEARCH_FOOD_ITEMS = "search_food_items"
    const val TOOL_GET_RESTAURANT_DETAILS = "get_restaurant_details"
    const val TOOL_GET_CART_SUMMARY = "get_cart_summary"
    const val TOOL_ADD_ITEM_TO_CART = "add_item_to_cart"
    const val TOOL_REMOVE_CART_ITEM = "remove_cart_item"
    const val TOOL_UPDATE_CART_QUANTITY = "update_cart_quantity"
    const val TOOL_NAVIGATE_TO_SCREEN = "navigate_to_screen"
    const val TOOL_GET_ORDER_STATUS = "get_order_status"

    /** Declares the typed FoodFlow actions Gemini may request during a conversation. */
    fun buildTools(): List<Tool> = listOf(
        Tool.functionDeclarations(
            listOf(
                FunctionDeclaration(
                    name = TOOL_SEARCH_RESTAURANTS,
                    description = "Search real restaurants on FoodFlow by cuisine, name, vegetarian filter, or rating.",
                    parameters = mapOf(
                        "query" to Schema.string("Restaurant name or cuisine keyword"),
                        "veg_only" to Schema.boolean("True if the user wants pure vegetarian restaurants"),
                        "min_rating" to Schema.double("Minimum star rating filter, e.g. 4.3")
                    ),
                    optionalParameters = listOf("query", "veg_only", "min_rating")
                ),
                FunctionDeclaration(
                    name = TOOL_SEARCH_FOOD_ITEMS,
                    description = "Search real available FoodFlow menu dishes by dish name, veg preference, max price in INR, spice level, meal type, or group size.",
                    parameters = mapOf(
                        "query" to Schema.string("Food keyword like pizza, paneer, dosa, biryani, burger"),
                        "veg_only" to Schema.boolean("True for vegetarian dishes only"),
                        "max_price" to Schema.double("Maximum price per dish in Indian Rupees"),
                        "spicy_only" to Schema.boolean("True when user asks for spicy or hot food"),
                        "meal_type" to Schema.string("Optional meal category such as breakfast, healthy, special"),
                        "for_people_count" to Schema.integer("Number of people, e.g. 2 for meal for two")
                    ),
                    optionalParameters = listOf("query", "veg_only", "max_price", "spicy_only", "meal_type", "for_people_count")
                ),
                FunctionDeclaration(
                    name = TOOL_GET_RESTAURANT_DETAILS,
                    description = "Get ratings, delivery time, and top available dishes for a specific FoodFlow restaurant.",
                    parameters = mapOf(
                        "restaurant_id" to Schema.string("Exact FoodFlow restaurant ID if known"),
                        "restaurant_name" to Schema.string("Restaurant name spoken by user")
                    ),
                    optionalParameters = listOf("restaurant_id", "restaurant_name")
                ),
                FunctionDeclaration(
                    name = TOOL_GET_CART_SUMMARY,
                    description = "Read the user's current FoodFlow shopping cart items, quantities, and payable total.",
                    parameters = emptyMap(),
                    optionalParameters = emptyList()
                ),
                FunctionDeclaration(
                    name = TOOL_ADD_ITEM_TO_CART,
                    description = "Add an existing available dish to the FoodFlow cart using food_id, food_name, or 1-based recommendation_index (e.g. 2 for 'second item').",
                    parameters = mapOf(
                        "food_id" to Schema.string("Exact food item ID if known"),
                        "food_name" to Schema.string("Dish name if spoken directly"),
                        "recommendation_index" to Schema.integer("1-based index from the most recent recommendation list (e.g. 1, 2, 3)"),
                        "quantity" to Schema.integer("Number of units to add, defaults to 1")
                    ),
                    optionalParameters = listOf("food_id", "food_name", "recommendation_index", "quantity")
                ),
                FunctionDeclaration(
                    name = TOOL_REMOVE_CART_ITEM,
                    description = "Remove an item from the user's FoodFlow cart by item_id or food_name.",
                    parameters = mapOf(
                        "item_id" to Schema.string("Cart item ID if known"),
                        "food_name" to Schema.string("Name of dish to remove from cart")
                    ),
                    optionalParameters = listOf("item_id", "food_name")
                ),
                FunctionDeclaration(
                    name = TOOL_UPDATE_CART_QUANTITY,
                    description = "Update the quantity of a dish already in the user's FoodFlow cart.",
                    parameters = mapOf(
                        "item_id" to Schema.string("Cart item ID if known"),
                        "food_name" to Schema.string("Dish name in cart"),
                        "quantity" to Schema.integer("New quantity (0 removes item)")
                    ),
                    optionalParameters = listOf("item_id", "food_name")
                ),
                FunctionDeclaration(
                    name = TOOL_NAVIGATE_TO_SCREEN,
                    description = "Navigate FoodFlow UI to home, search, cart, orders, profile, scheduled_orders, checkout, order_tracking, or restaurant_detail.",
                    parameters = mapOf(
                        "destination" to Schema.string("Screen destination key: home, search, cart, orders, profile, scheduled_orders, checkout, order_tracking, restaurant_detail"),
                        "restaurant_id" to Schema.string("Restaurant ID when destination is restaurant_detail"),
                        "restaurant_name" to Schema.string("Restaurant name when destination is restaurant_detail"),
                        "order_id" to Schema.string("Order ID when destination is order_tracking")
                    ),
                    optionalParameters = listOf("restaurant_id", "restaurant_name", "order_id")
                ),
                FunctionDeclaration(
                    name = TOOL_GET_ORDER_STATUS,
                    description = "Check live order status and ETA for the user's active or latest FoodFlow order.",
                    parameters = mapOf(
                        "order_id" to Schema.string("Optional specific order ID")
                    ),
                    optionalParameters = listOf("order_id")
                )
            )
        )
    )

    /** Converts a Firebase function call into the app's transport-independent tool model. */
    fun toDomainInvocation(part: FunctionCallPart): VoiceToolCallInvocation {
        val stringArgs = part.args.mapValues { (_, v) ->
            runCatching { v.jsonPrimitive.contentOrNull ?: v.toString() }.getOrDefault(v.toString())
        }
        return VoiceToolCallInvocation(
            callId = part.id ?: "${part.name}_${System.nanoTime()}",
            name = part.name,
            args = stringArgs
        )
    }

    /** Validates tool name and arguments, then maps supported calls to domain actions. */
    fun toDomainActionRequest(invocation: VoiceToolCallInvocation): VoiceActionRequest? {
        val a = invocation.args
        return when (invocation.name) {
            TOOL_SEARCH_RESTAURANTS -> VoiceActionRequest.SearchRestaurants(
                query = a["query"].orEmpty(),
                vegOnly = a["veg_only"]?.equals("true", ignoreCase = true) == true,
                minRating = a["min_rating"]?.toDoubleOrNull()
            )
            TOOL_SEARCH_FOOD_ITEMS -> VoiceActionRequest.SearchFoodItems(
                query = a["query"].orEmpty(),
                vegOnly = a["veg_only"]?.equals("true", ignoreCase = true) == true,
                maxPrice = a["max_price"]?.toDoubleOrNull(),
                spicyOnly = a["spicy_only"]?.equals("true", ignoreCase = true) == true,
                mealType = a["meal_type"],
                forPeopleCount = a["for_people_count"]?.toIntOrNull()
            )
            TOOL_GET_RESTAURANT_DETAILS -> VoiceActionRequest.GetRestaurantDetails(
                restaurantId = a["restaurant_id"],
                restaurantName = a["restaurant_name"]
            )
            TOOL_GET_CART_SUMMARY -> VoiceActionRequest.GetCartSummary
            TOOL_ADD_ITEM_TO_CART -> VoiceActionRequest.AddItemToCart(
                foodId = a["food_id"],
                foodName = a["food_name"],
                recommendationIndex = a["recommendation_index"]?.toIntOrNull(),
                quantity = a["quantity"]?.toIntOrNull() ?: 1
            )
            TOOL_REMOVE_CART_ITEM -> VoiceActionRequest.RemoveCartItem(
                itemId = a["item_id"],
                foodName = a["food_name"]
            )
            TOOL_UPDATE_CART_QUANTITY -> VoiceActionRequest.UpdateCartQuantity(
                itemId = a["item_id"],
                foodName = a["food_name"],
                quantity = a["quantity"]?.toIntOrNull() ?: 1
            )
            TOOL_NAVIGATE_TO_SCREEN -> VoiceActionRequest.NavigateToScreen(
                destination = a["destination"].orEmpty(),
                restaurantId = a["restaurant_id"],
                restaurantName = a["restaurant_name"],
                orderId = a["order_id"]
            )
            TOOL_GET_ORDER_STATUS -> VoiceActionRequest.GetOrderStatus(
                orderId = a["order_id"]
            )
            else -> null
        }
    }

    /** Converts a domain action result into the response frame Gemini expects for a tool call. */
    fun toFunctionResponsePart(
        invocation: VoiceToolCallInvocation,
        result: VoiceActionResult
    ): FunctionResponsePart {
        val jsonPayload: JsonObject = buildJsonObject {
            put("success", result.success)
            put("summary", result.summaryForVoice)
            put("requires_user_confirmation", result.requiresConfirmation != null)
            result.structuredPayload.forEach { (k, v) ->
                put(k, JsonPrimitive(v))
            }
        }
        return FunctionResponsePart(
            name = invocation.name,
            response = jsonPayload,
            id = invocation.callId
        )
    }

    /** Builds the live voice system prompt with FoodFlow scope and current context. */
    fun buildSystemInstruction(contextSummary: String): String = buildString {
        appendLine("You are FoodFlow Voice, a friendly, concise, voice-first food ordering assistant inside the FoodFlow Android app.")
        appendLine("Strict rules:")
        appendLine("1. NEVER invent food items, prices, ratings, or restaurant availability. Always call the provided FoodFlow tools to read live menu, restaurant, cart, and order data.")
        appendLine("2. Speak naturally and concisely (2-3 short sentences max per turn) because your answer is played aloud in real time.")
        appendLine("3. Currency is Indian Rupees (₹ / rupees). When recommending dishes, number them 1, 2, 3 so the user can say 'add the second item to my cart'.")
        appendLine("4. Never silently place orders or charge payment. For checkout or replacing a cart from a different restaurant, explain and ask for confirmation.")
        appendLine("5. If the user interrupts you mid-sentence, immediately stop the previous topic and answer the new request using prior context.")
        if (contextSummary.isNotBlank()) {
            appendLine()
            appendLine("Current FoodFlow session context:")
            appendLine(contextSummary)
        }
    }

    /** Builds the text assistant system prompt with supported workflows and current context. */
    fun buildTextSystemInstruction(contextSummary: String): String = buildString {
        appendLine("You are FoodFlow Assistant, a friendly, concise text food ordering assistant inside the FoodFlow Android app.")
        appendLine("Strict rules:")
        appendLine("1. NEVER invent food items, prices, ratings, or restaurant availability. Always call the provided FoodFlow tools to read live menu, restaurant, cart, and order data.")
        appendLine("2. Reply in clear, concise written text (2-4 short sentences max per turn).")
        appendLine("3. Currency is Indian Rupees (₹ / rupees). When recommending dishes, number them 1, 2, 3 so the user can easily refer to 'the second item'.")
        appendLine("4. Never silently place orders or charge payment. For checkout or replacing a cart from a different restaurant, explain and ask for confirmation.")
        if (contextSummary.isNotBlank()) {
            appendLine()
            appendLine("Current FoodFlow session context:")
            appendLine(contextSummary)
        }
    }
}
