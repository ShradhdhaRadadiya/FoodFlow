package com.foodflow.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.designsystem.components.FoodFlowLogoLockup
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.foodFlowKeyboardAndNavInsets
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import kotlinx.coroutines.flow.collectLatest

/** Displays the phone-number and verification-code sign-in flow. */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                AuthEffect.NavigateToHome -> onLoginSuccess()
                is AuthEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .foodFlowKeyboardAndNavInsets()
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                colorScheme.primaryContainer,
                                colorScheme.secondaryContainer.copy(alpha = 0.72f)
                            )
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = FoodFlowSpacing.lg, vertical = FoodFlowSpacing.xl)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FoodFlowLogoLockup(
                        markSize = 52.dp,
                        showTagline = false
                    )
                    Text(
                        text = "Welcome to FoodFlow",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Sign in with your mobile number to order fresh meals and track deliveries live.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FoodFlowSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.md)
            ) {
                if (state.isOffline) {
                    OfflineBanner(isOffline = true)
                }

                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
                    border = BorderStroke(0.75.dp, colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (!state.isOtpSent) "Enter Mobile Number" else "Verify OTP",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (!state.isOtpSent) {
                            OutlinedTextField(
                                value = state.phoneNumber,
                                onValueChange = { viewModel.onIntent(AuthIntent.PhoneChanged(it)) },
                                label = { Text("Mobile Number") },
                                prefix = { Text("+91 ") },
                                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_phone_input")
                            )

                            Button(
                                onClick = { viewModel.onIntent(AuthIntent.SendOtp) },
                                enabled = !state.isLoading,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("auth_send_otp_button")
                            ) {
                                if (state.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Continue with OTP")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Code sent to +91 ${state.phoneNumber}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { viewModel.onIntent(AuthIntent.EditPhone) }) {
                                    Text("Change")
                                }
                            }

                            OutlinedTextField(
                                value = state.otpCode,
                                onValueChange = { viewModel.onIntent(AuthIntent.OtpChanged(it)) },
                                label = { Text("6-digit OTP") },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_otp_input")
                            )

                            Button(
                                onClick = { viewModel.onIntent(AuthIntent.VerifyOtp) },
                                enabled = !state.isLoading,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("auth_verify_otp_button")
                            ) {
                                if (state.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Verify & Sign In")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { viewModel.onIntent(AuthIntent.SendOtp) },
                                    enabled = state.resendSecondsRemaining == 0
                                ) {
                                    Text(
                                        if (state.resendSecondsRemaining > 0) {
                                            "Resend code in ${state.resendSecondsRemaining}s"
                                        } else {
                                            "Resend OTP"
                                        }
                                    )
                                }
                            }
                        }

                        state.errorMessage?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.error,
                                modifier = Modifier.testTag("auth_error_text")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                OutlinedButton(
                    onClick = { viewModel.onIntent(AuthIntent.QuickContinue) },
                    enabled = !state.isLoading,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("auth_quick_demo_button")
                ) {
                    Text("Continue with Saved Account", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
