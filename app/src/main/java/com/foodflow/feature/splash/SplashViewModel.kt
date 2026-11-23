package com.foodflow.feature.splash

import androidx.lifecycle.viewModelScope
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.usecase.auth.EnsureCatalogSeededUseCase
import com.foodflow.domain.usecase.auth.ObserveAuthPreferencesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val ensureCatalogSeeded: EnsureCatalogSeededUseCase,
    private val observeAuthPreferences: ObserveAuthPreferencesUseCase
) : MviViewModel<SplashUiState, SplashIntent, SplashEffect>(SplashUiState()) {

    internal var minDisplayDurationMs: Long = DEFAULT_SPLASH_DURATION_MS

    init {
        onIntent(SplashIntent.Initialize)
    }

    /** Starts app initialization or applies the reduced-motion splash duration. */
    override fun onIntent(intent: SplashIntent) {
        when (intent) {
            SplashIntent.Initialize -> runInitialization()
            SplashIntent.UseReducedMotionDuration -> {
                minDisplayDurationMs = SplashStoryTimeline.REDUCED_MOTION_DURATION_MS
            }
        }
    }

    /** Loads the local catalog, reads sign-in state, and routes after the splash delay. */
    private fun runInitialization() {
        viewModelScope.launch {
            val seedJob = async { ensureCatalogSeeded() }
            val startMs = System.currentTimeMillis()
            seedJob.await()
            val prefs = observeAuthPreferences().first()
            val elapsedMs = System.currentTimeMillis() - startMs
            val remainingMs = (minDisplayDurationMs - elapsedMs).coerceAtLeast(0L)
            if (remainingMs > 0L) {
                delay(remainingMs)
            }
            updateState { copy(isReady = true) }
            if (prefs.isLoggedIn) {
                emitEffect(SplashEffect.NavigateToHome)
            } else {
                emitEffect(SplashEffect.NavigateToLogin)
            }
        }
    }

    companion object {
        const val DEFAULT_SPLASH_DURATION_MS: Long = SplashStoryTimeline.TOTAL_STORY_DURATION_MS
    }
}
