package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.foodflow.app.configureAppCheck
import com.foodflow.app.navigation.FoodFlowRoot
import com.foodflow.core.datastore.ThemeMode
import com.foodflow.core.datastore.UserPreferences
import com.foodflow.core.datastore.UserPreferencesDataStore
import com.foodflow.core.designsystem.theme.FoodFlowTheme
import com.foodflow.domain.repository.VoiceAssistantController
import com.foodflow.domain.usecase.scheduled.ReconcileOverdueSchedulesUseCase
import com.foodflow.domain.usecase.tracking.ResumeActiveOrderTrackingUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FoodFlow Launcher Activity.
 * Installs Android 12+ SplashScreen API before super.onCreate() so there is zero
 * pre-splash black screen, then delegates Compose UI to `com.foodflow.*`.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesDataStore: UserPreferencesDataStore

    @Inject
    lateinit var resumeActiveOrderTracking: ResumeActiveOrderTrackingUseCase

    @Inject
    lateinit var reconcileOverdueSchedules: ReconcileOverdueSchedulesUseCase

    @Inject
    lateinit var voiceAssistantController: VoiceAssistantController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Configure Firebase App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            val prefs by preferencesDataStore.preferences.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )
            val isDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoodFlowTheme(darkTheme = isDark) {
                FoodFlowRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        voiceAssistantController.onAppForegrounded()
        resumeActiveOrderTracking()
        lifecycleScope.launch {
            runCatching { reconcileOverdueSchedules() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            voiceAssistantController.onAppBackgrounded()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            voiceAssistantController.releaseResources()
        }
        super.onDestroy()
    }
}
