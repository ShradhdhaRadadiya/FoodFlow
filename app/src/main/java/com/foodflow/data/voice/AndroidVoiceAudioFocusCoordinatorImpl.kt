package com.foodflow.data.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.foodflow.domain.model.VoiceAudioRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceAudioFocus"

/**
 * Coordinates Android AudioFocus, output routing (Speaker, Bluetooth, Wired Headset),
 * and ACTION_AUDIO_BECOMING_NOISY without trapping AI speech in low-volume earpiece mode.
 * Never mutates system media volume programmatically.
 */
@Singleton
class AndroidVoiceAudioFocusCoordinatorImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceAudioFocusCoordinator {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _hasFocus = MutableStateFlow(false)
    override val hasFocus: StateFlow<Boolean> = _hasFocus.asStateFlow()

    private val _activeRoute = MutableStateFlow(VoiceAudioRoute.SPEAKER)
    override val activeRoute: StateFlow<VoiceAudioRoute> = _activeRoute.asStateFlow()

    private val _focusEvents = MutableSharedFlow<VoiceAudioFocusEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val focusEvents: SharedFlow<VoiceAudioFocusEvent> = _focusEvents.asSharedFlow()

    private var focusRequest: AudioFocusRequest? = null
    private var receiversRegistered = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                _hasFocus.value = true
                _focusEvents.tryEmit(VoiceAudioFocusEvent.GAINED)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                _hasFocus.value = false
                _focusEvents.tryEmit(VoiceAudioFocusEvent.LOST_PERMANENT)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                _hasFocus.value = false
                _focusEvents.tryEmit(VoiceAudioFocusEvent.LOST_TRANSIENT)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _focusEvents.tryEmit(VoiceAudioFocusEvent.LOST_TRANSIENT_CAN_DUCK)
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                refreshActiveRoute()
                _focusEvents.tryEmit(VoiceAudioFocusEvent.BECOMING_NOISY)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshActiveRoute()
            _focusEvents.tryEmit(VoiceAudioFocusEvent.ROUTE_CHANGED)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshActiveRoute()
            _focusEvents.tryEmit(VoiceAudioFocusEvent.ROUTE_CHANGED)
        }
    }

    /** Requests Android audio focus and refreshes the active output route. */
    override fun requestAudioFocus(): Boolean {
        registerRouteMonitoringIfNeeded()
        if (_hasFocus.value) {
            refreshActiveRoute()
            return true
        }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        _hasFocus.value = granted
        if (granted) {
            // Keep MODE_NORMAL so USAGE_ASSISTANT speech routes through full-volume speaker,
            // Bluetooth A2DP/LE Audio, or wired headphones rather than quiet earpiece call audio.
            runCatching {
                if (audioManager.mode != AudioManager.MODE_NORMAL) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
                refreshActiveRoute()
            }.onFailure { runCatching { Log.w(TAG, "Could not refresh audio route", it) } }
        }
        return granted
    }

    /** Abandons focus and restores normal audio routing. */
    override fun abandonAudioFocus() {
        _hasFocus.value = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }.onFailure { runCatching { Log.w(TAG, "Failed abandoning audio focus", it) } }
    }

    /** Releases focus and unregisters audio-route listeners. */
    override fun release() {
        abandonAudioFocus()
        if (receiversRegistered) {
            runCatching { context.unregisterReceiver(becomingNoisyReceiver) }
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            receiversRegistered = false
        }
    }

    /** Registers device and noisy-output listeners once for this coordinator. */
    private fun registerRouteMonitoringIfNeeded() {
        if (receiversRegistered) return
        runCatching {
            context.registerReceiver(
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            receiversRegistered = true
        }.onFailure { runCatching { Log.w(TAG, "Audio route monitoring unavailable", it) } }
        refreshActiveRoute()
    }

    /** Detects whether output currently routes to Bluetooth, wired audio, or speaker. */
    private fun refreshActiveRoute() {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
        val route = when {
            outputs?.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            } == true -> VoiceAudioRoute.BLUETOOTH

            outputs?.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } == true -> VoiceAudioRoute.WIRED_HEADSET

            else -> VoiceAudioRoute.SPEAKER
        }
        _activeRoute.value = route
    }
}
