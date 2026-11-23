package com.foodflow.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.domain.model.VoiceAudioChunk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val TAG = "VoiceAudioRecord"

/**
 * Dedicated Android AudioRecord streaming capture manager.
 * - Captures 16 kHz, 16-bit mono PCM (Gemini Live native input format) in fixed 40 ms chunks (1280 bytes).
 * - Uses VOICE_COMMUNICATION source + hardware AcousticEchoCanceler, NoiseSuppressor, and
 *   AutomaticGainControl when supported by the device, attached strictly to the AudioRecord session ID.
 * - Single producer coroutine on dispatchers.io with bounded Channel(16, DROP_OLDEST) backpressure.
 */
@Singleton
class AndroidVoiceAudioRecordingDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) : VoiceAudioRecordingDataSource {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 40ms @ 16kHz 16-bit mono = 640 samples * 2 bytes = 1280 bytes
        const val CHUNK_SIZE_BYTES = 1280
        private const val CHANNEL_CAPACITY = 16
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val sequenceGenerator = AtomicLong(0L)
    private val recordLock = Any()

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var producerJob: Job? = null
    private var chunkChannel: Channel<VoiceAudioChunk>? = null

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _inputAmplitude = MutableStateFlow(0f)
    override val inputAmplitude: StateFlow<Float> = _inputAmplitude.asStateFlow()

    private val _activeAudioEffectsSummary = MutableStateFlow("Not initialized")
    override val activeAudioEffectsSummary: StateFlow<String> = _activeAudioEffectsSummary.asStateFlow()

    /** Checks whether the app currently has Android microphone permission. */
    override fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    /** Starts microphone capture and returns a flow of fixed-size PCM chunks. */
    override fun startRecording(turnIdProvider: () -> Long): Result<Flow<VoiceAudioChunk>> {
        if (!hasRecordPermission()) {
            return Result.failure(SecurityException("RECORD_AUDIO permission is not granted"))
        }

        // Prevent duplicate AudioRecord instances
        stopRecording()

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            return Result.failure(IllegalStateException("Unsupported audio format on this device"))
        }
        val bufferSize = maxOf(minBuffer * 2, CHUNK_SIZE_BYTES * 4)

        return synchronized(recordLock) {
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (se: SecurityException) {
                return@synchronized Result.failure(se)
            } catch (e: Exception) {
                return@synchronized Result.failure(
                    IllegalStateException("Microphone initialization failed: ${e.message}", e)
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                return@synchronized Result.failure(IllegalStateException("AudioRecord failed to initialize"))
            }

            attachHardwareAudioEffects(record.audioSessionId)

            val channel = Channel<VoiceAudioChunk>(
                capacity = CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            audioRecord = record
            chunkChannel = channel

            try {
                record.startRecording()
            } catch (e: Exception) {
                stopRecordingLocked()
                return@synchronized Result.failure(IllegalStateException("Unable to start microphone capture", e))
            }

            _isRecording.value = true
            sequenceGenerator.set(0L)

            // Single dedicated producer coroutine — zero per-chunk coroutine launches
            producerJob = scope.launch {
                val readBuffer = ByteArray(CHUNK_SIZE_BYTES)
                try {
                    while (isActive && _isRecording.value && audioRecord === record) {
                        val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                        if (audioRecord !== record || !_isRecording.value) break
                        when {
                            bytesRead > 0 -> {
                                val chunkBytes = readBuffer.copyOf(bytesRead)
                                val rms = computeNormalizedRms(chunkBytes, bytesRead)
                                _inputAmplitude.value = rms
                                val chunk = VoiceAudioChunk(
                                    sequenceNumber = sequenceGenerator.incrementAndGet(),
                                    timestampMs = System.currentTimeMillis(),
                                    turnId = turnIdProvider(),
                                    pcmBytes = chunkBytes,
                                    sampleRateHz = SAMPLE_RATE_HZ,
                                    rmsAmplitude = rms
                                )
                                channel.trySend(chunk)
                            }
                            bytesRead == AudioRecord.ERROR_INVALID_OPERATION ||
                                bytesRead == AudioRecord.ERROR_BAD_VALUE ||
                                bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e(TAG, "AudioRecord read error code=$bytesRead")
                                break
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (se: SecurityException) {
                    Log.e(TAG, "Microphone permission revoked while recording", se)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected recording loop failure", e)
                } finally {
                    if (audioRecord === record || audioRecord == null) {
                        _inputAmplitude.value = 0f
                        _isRecording.value = false
                    }
                    channel.close()
                }
            }

            Result.success(channel.receiveAsFlow())
        }
    }

    /** Stops active capture and closes its chunk stream. */
    override fun stopRecording() {
        synchronized(recordLock) {
            stopRecordingLocked()
        }
    }

    /** Releases recording resources while the caller holds the recording lock. */
    private fun stopRecordingLocked() {
        _isRecording.value = false
        _inputAmplitude.value = 0f
        val activeRec = audioRecord
        audioRecord = null
        // Stop AudioRecord first to unblock any pending native read() before cancelling & releasing
        activeRec?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
        }
        producerJob?.cancel()
        producerJob = null
        chunkChannel?.close()
        chunkChannel = null
        releaseAudioEffects()
        activeRec?.let { rec ->
            runCatching { rec.release() }
        }
    }

    /** Stops capture and releases hardware audio effects. */
    override fun release() {
        stopRecording()
    }

    /** Attaches supported echo cancellation, noise suppression, and gain control. */
    private fun attachHardwareAudioEffects(audioSessionId: Int) {
        releaseAudioEffects()
        val enabled = mutableListOf<String>()

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.apply {
                    setEnabled(true)
                    echoCanceler = this
                    enabled += "AEC"
                }
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(audioSessionId)?.apply {
                    setEnabled(true)
                    noiseSuppressor = this
                    enabled += "NS"
                }
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            runCatching {
                AutomaticGainControl.create(audioSessionId)?.apply {
                    setEnabled(true)
                    gainControl = this
                    enabled += "AGC"
                }
            }
        }
        _activeAudioEffectsSummary.value = if (enabled.isEmpty()) {
            "Hardware effects unavailable (raw VOICE_COMMUNICATION)"
        } else {
            "Hardware ${enabled.joinToString(" + ")}"
        }
    }

    /** Releases any hardware audio effects attached to the recorder. */
    private fun releaseAudioEffects() {
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { gainControl?.release() }
        gainControl = null
    }

    /** Calculates input amplitude from the captured PCM samples. */
    private fun computeNormalizedRms(pcm16Le: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = length / 2
        var i = 0
        while (i + 1 < length) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            i += 2
        }
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return (rms * 4.5f).coerceIn(0f, 1f)
    }
}
