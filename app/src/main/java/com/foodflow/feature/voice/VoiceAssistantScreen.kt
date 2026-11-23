package com.foodflow.feature.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.app.ActivityCompat

/**
 * Single root Voice Assistant screen overlay mounted once in FoodFlowRoot.
 * Coordinates the draggable floating trigger button (persisting its screen position
 * across navigation and recomposition), the unified persistent bottom sheet,
 * and runtime RECORD_AUDIO permission handling.
 */
@Composable
fun VoiceAssistantScreen(
    uiState: VoiceAssistantUiState,
    showFloatingTrigger: Boolean,
    floatingBottomOffset: Dp,
    requestPermissionSignal: Int,
    onIntent: (VoiceAssistantIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnIntent by rememberUpdatedState(onIntent)
    var fabSnappedRight by rememberSaveable { mutableStateOf(true) }
    var fabVerticalFraction by rememberSaveable { mutableStateOf<Float?>(null) }

    DisposableEffect(context) {
        onDispose {
            // Close active voice session when VoiceAssistantScreen leaves composition,
            // while preserving session continuity across configuration changes (e.g., rotation).
            if (context.findActivity()?.isChangingConfigurations != true) {
                currentOnIntent(VoiceAssistantIntent.CloseAssistant)
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val activity = context.findActivity()
        val permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
        onIntent(
            VoiceAssistantIntent.PermissionResult(
                granted = granted,
                permanentlyDenied = permanentlyDenied
            )
        )
    }

    LaunchedEffect(requestPermissionSignal) {
        if (requestPermissionSignal > 0) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        VoiceAssistantWidget(
            uiState = uiState,
            visible = showFloatingTrigger,
            bottomOffset = floatingBottomOffset,
            snappedRight = fabSnappedRight,
            verticalFraction = fabVerticalFraction,
            onPositionChanged = { snappedRight, yFraction ->
                fabSnappedRight = snappedRight
                fabVerticalFraction = yFraction
            },
            onOpenAssistant = { onIntent(VoiceAssistantIntent.OpenAssistant) }
        )

        VoiceAssistantBottomSheet(
            uiState = uiState,
            onIntent = onIntent
        )
    }
}

/** Finds the hosting Activity by walking through nested Context wrappers. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
