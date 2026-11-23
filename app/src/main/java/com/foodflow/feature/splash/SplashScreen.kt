package com.foodflow.feature.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

/** Shows startup progress and routes the user after app initialization. */
@Composable
fun SplashScreen(
    onNavigateToHome: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }

    val storyProgress = remember { Animatable(if (reducedMotion) 1f else 0f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            viewModel.onIntent(SplashIntent.UseReducedMotionDuration)
            storyProgress.snapTo(1f)
        } else {
            storyProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SplashStoryTimeline.TOTAL_STORY_DURATION_MS.toInt(),
                    easing = LinearEasing
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                SplashEffect.NavigateToHome -> onNavigateToHome()
                SplashEffect.NavigateToLogin -> onNavigateToLogin()
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.background,
                        colorScheme.primaryContainer.copy(alpha = 0.48f),
                        colorScheme.surface
                    )
                )
            )
            .safeDrawingPadding()
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        SplashStoryScene(
            progress = storyProgress.value,
            reducedMotion = reducedMotion
        )
    }
}
