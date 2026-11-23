package com.foodflow.feature.splash

enum class SplashStoryStage(
    val stepIndex: Int,
    val caption: String,
    val startFraction: Float,
    val endFraction: Float
) {
    KITCHEN_COOKING(
        stepIndex = 0,
        caption = "Preparing fresh flavours",
        startFraction = 0.00f,
        endFraction = 0.24f
    ),
    PARTNER_PICKUP(
        stepIndex = 1,
        caption = "Packed & picked up by courier",
        startFraction = 0.24f,
        endFraction = 0.44f
    ),
    ROAD_DELIVERY(
        stepIndex = 2,
        caption = "On the road to you",
        startFraction = 0.44f,
        endFraction = 0.70f
    ),
    DOORSTEP_HANDOVER(
        stepIndex = 3,
        caption = "Delivered to your doorstep",
        startFraction = 0.70f,
        endFraction = 0.85f
    ),
    BRAND_FINALE(
        stepIndex = 4,
        caption = "Fresh flavours, delivered effortlessly",
        startFraction = 0.85f,
        endFraction = 1.00f
    )
}

object SplashStoryTimeline {
    const val TOTAL_STORY_DURATION_MS: Long = 3_000L
    const val REDUCED_MOTION_DURATION_MS: Long = 850L

    /** Finds which delivery-story stage corresponds to the animation progress. */
    fun stageForProgress(progress: Float): SplashStoryStage {
        val clamped = progress.coerceIn(0f, 1f)
        return SplashStoryStage.entries.firstOrNull { stage ->
            clamped < stage.endFraction || stage == SplashStoryStage.BRAND_FINALE
        } ?: SplashStoryStage.BRAND_FINALE
    }

    /** Converts overall animation progress into progress within one story stage. */
    fun localStageProgress(progress: Float, stage: SplashStoryStage): Float {
        val span = (stage.endFraction - stage.startFraction).coerceAtLeast(0.001f)
        return ((progress - stage.startFraction) / span).coerceIn(0f, 1f)
    }
}
