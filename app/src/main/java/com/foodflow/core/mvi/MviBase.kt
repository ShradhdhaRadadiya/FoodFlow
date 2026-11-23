package com.foodflow.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Marker interface for immutable MVI UI state models.
 */
interface MviState

/**
 * Marker interface for user/lifecycle MVI intents.
 */
interface MviIntent

/**
 * Marker interface for one-shot MVI side effects (navigation, snackbars, intents).
 */
interface MviEffect

/**
 * Standardized MVI base ViewModel enforcing unidirectional data flow:
 * Intent -> ViewModel -> immutable StateFlow + buffered Channel one-time Effects.
 */
abstract class MviViewModel<S : MviState, I : MviIntent, E : MviEffect>(
    initialState: S
) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()
    protected val currentState: S get() = _state.value

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    /** Handles one UI or lifecycle intent and updates state or emits a one-shot effect. */
    abstract fun onIntent(intent: I)

    /** Applies a reducer atomically so observers receive the next immutable state. */
    protected fun updateState(reducer: S.() -> S) {
        _state.update(reducer)
    }

    /** Sends a one-time effect through the buffered channel from the ViewModel scope. */
    protected fun emitEffect(effect: E) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }
}
