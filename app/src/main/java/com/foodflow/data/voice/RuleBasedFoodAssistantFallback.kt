package com.foodflow.data.voice

import com.foodflow.domain.model.VoiceActionRequest
import com.foodflow.domain.model.VoiceActionResult
import com.foodflow.domain.model.VoiceConversationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transparent local rule-based fallback using actual FoodFlow Room data when Gemini Live
 * is unavailable due to quota exhaustion, offline state, or network failure.
 * Never pretends to be Gemini and never invents food items, prices, or availability.
 */
@Singleton
class RuleBasedFoodAssistantFallback @Inject constructor(
    private val actionExecutor: VoiceAssistantActionDataSource
) : VoiceLocalFallbackEngine {

    private val budgetRegex = Regex("""(?:under|below|less than|within|max|₹|rs\.?\s*)\s*₹?\s*(\d{2,4})""", RegexOption.IGNORE_CASE)
    private val trailingRupeesRegex = Regex("""(\d{2,4})\s*(?:rupees|rs|inr)""", RegexOption.IGNORE_CASE)

    /** Answers supported food queries using local repositories when the Gemini path fails. */
    override suspend fun handleFallbackQuery(
        userQuery: String,
        context: VoiceConversationContext
    ): Pair<VoiceActionResult, String> {
        val q = userQuery.trim()
        val lower = q.lowercase()

        // 1. Sensitive checkout/place-order requests always require explicit confirmation
        if (lower.contains("place order") || lower.contains("pay now") || lower.contains("make payment")) {
            val navResult = actionExecutor.execute(
                VoiceActionRequest.NavigateToScreen(destination = "checkout"),
                context
            )
            return navResult to "For your security, please review and confirm payment directly on the Checkout screen."
        }

        // 2. Cart summary
        if (lower.contains("cart") && (lower.contains("what") || lower.contains("show") || lower.contains("summary") || lower.contains("view") || lower.contains("open"))) {
            val cartResult = actionExecutor.execute(VoiceActionRequest.GetCartSummary, context)
            val navTarget = if (lower.contains("open") || lower.contains("show") || lower.contains("go to")) {
                actionExecutor.execute(VoiceActionRequest.NavigateToScreen("cart"), context).navigationTarget
            } else null
            return cartResult.copy(navigationTarget = navTarget) to "${cartResult.summaryForVoice}"
        }

        // 3. Order status / tracking
        if (lower.contains("order status") || lower.contains("where is my order") || lower.contains("track my order")) {
            val statusResult = actionExecutor.execute(VoiceActionRequest.GetOrderStatus(), context)
            return statusResult to "${statusResult.summaryForVoice}"
        }

        // 4. Add recommended or named item to cart
        if (lower.contains("add ") && (lower.contains("cart") || lower.contains("order") || extractOrdinal(lower) != null)) {
            val ordinal = extractOrdinal(lower)
            val action = if (ordinal != null) {
                VoiceActionRequest.AddItemToCart(recommendationIndex = ordinal)
            } else {
                val cleanedFood = lower
                    .replace("add ", "")
                    .replace("to my cart", "")
                    .replace("to cart", "")
                    .replace("a ", "")
                    .replace("an ", "")
                    .replace("the ", "")
                    .trim()
                VoiceActionRequest.AddItemToCart(foodName = cleanedFood)
            }
            val addRes = actionExecutor.execute(action, context)
            return addRes to "${addRes.summaryForVoice}"
        }

        // 5. Follow-up referencing ordinal item ("what about the second one?")
        val ordinalFollowUp = extractOrdinal(lower)
        if (ordinalFollowUp != null && (lower.contains("about") || lower.contains("second") || lower.contains("first") || lower.contains("third") || lower.contains("open") || lower.contains("detail"))) {
            val rec = context.recentRecommendations.firstOrNull { it.index == ordinalFollowUp }
                ?: context.recentRecommendations.getOrNull(ordinalFollowUp - 1)
            if (rec != null) {
                val detail = actionExecutor.execute(
                    VoiceActionRequest.GetRestaurantDetails(restaurantId = rec.restaurantId),
                    context
                )
                val info = "${rec.foodName} from ${rec.restaurantName} is ₹${rec.price.toInt()} (${if (rec.isVeg) "Veg" else "Non-Veg"}, ${rec.spiceLevel} spice, rated ${"%.1f".format(rec.rating)}). Say 'add the ${ordinalWord(ordinalFollowUp)} item to my cart' to add it."
                return detail.copy(summaryForVoice = info) to "$info"
            }
        }

        // 6. Remove from cart
        if (lower.contains("remove ") && lower.contains("cart")) {
            val targetName = lower
                .replace("remove ", "")
                .replace("from my cart", "")
                .replace("from cart", "")
                .trim()
            val remRes = actionExecutor.execute(VoiceActionRequest.RemoveCartItem(foodName = targetName), context)
            return remRes to "${remRes.summaryForVoice}"
        }

        // 7. Navigate to restaurant or screen
        if (lower.startsWith("open ") || lower.startsWith("go to ") || lower.startsWith("navigate to ")) {
            val destPhrase = lower.removePrefix("open ").removePrefix("go to ").removePrefix("navigate to ").trim()
            val isScreenKeyword = listOf("home", "search", "cart", "orders", "profile", "checkout", "scheduled", "track")
                .any { destPhrase.contains(it) }
            val navAction = if (isScreenKeyword) {
                VoiceActionRequest.NavigateToScreen(destination = destPhrase)
            } else {
                VoiceActionRequest.NavigateToScreen(destination = "restaurant", restaurantName = destPhrase)
            }
            val navRes = actionExecutor.execute(navAction, context)
            return navRes to "${navRes.summaryForVoice}"
        }

        // 8. Restaurant search
        if (lower.contains("restaurant")) {
            val vegOnly = lower.contains("veg") && !lower.contains("non-veg") && !lower.contains("non veg")
            val query = lower.replace("find", "").replace("show", "").replace("restaurants", "")
                .replace("restaurant", "").replace("vegetarian", "").replace("veg", "").trim()
            val res = actionExecutor.execute(
                VoiceActionRequest.SearchRestaurants(query = query, vegOnly = vegOnly),
                context
            )
            return res to "${res.summaryForVoice}"
        }

        // 9. Food search (veg / budget / spicy / breakfast / special / meal for two / keyword)
        val vegOnly = (lower.contains("vegetarian") || lower.contains("pure veg") || Regex("""\bveg\b""").containsMatchIn(lower)) &&
            !lower.contains("non-veg") && !lower.contains("non veg")
        val maxPrice = budgetRegex.find(lower)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: trailingRupeesRegex.find(lower)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val spicyOnly = lower.contains("spicy") || lower.contains("hot")
        val mealType = when {
            lower.contains("breakfast") || lower.contains("healthy") -> "breakfast"
            lower.contains("special") || lower.contains("recommend") || lower.contains("popular") -> "special"
            else -> null
        }
        val forPeople = if (lower.contains("for two") || lower.contains("for 2")) 2 else null

        val dishKeywords = listOf("pizza", "biryani", "burger", "paneer", "dosa", "pasta", "noodles", "momos", "thali", "tikka", "roll", "salad", "dessert", "cake", "coffee", "shake", "chicken", "dal", "naan")
        val matchedKeyword = dishKeywords.firstOrNull { lower.contains(it) }.orEmpty()

        val searchRes = actionExecutor.execute(
            VoiceActionRequest.SearchFoodItems(
                query = matchedKeyword,
                vegOnly = vegOnly,
                maxPrice = maxPrice,
                spicyOnly = spicyOnly,
                mealType = mealType,
                forPeopleCount = forPeople
            ),
            context
        )
        return searchRes to "${searchRes.summaryForVoice}"
    }

    /** Extracts a one-based item position from numeric or spoken ordinal wording. */
    private fun extractOrdinal(lower: String): Int? = when {
        Regex("""\b(first|1st)\b""").containsMatchIn(lower) -> 1
        Regex("""\b(second|2nd)\b""").containsMatchIn(lower) -> 2
        Regex("""\b(third|3rd)\b""").containsMatchIn(lower) -> 3
        Regex("""\b(fourth|4th)\b""").containsMatchIn(lower) -> 4
        Regex("""\b(fifth|5th)\b""").containsMatchIn(lower) -> 5
        else -> null
    }

    /** Converts a small one-based item index to the word used in fallback responses. */
    private fun ordinalWord(n: Int): String = when (n) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        else -> "fifth"
    }
}
