package com.foodflow.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.domain.model.VoiceAudioChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val TAG = "VoiceAudioTrack"

/**
 * Dedicated streaming AudioTrack playback engine for 24 kHz 16-bit mono PCM audio from Gemini Live.
 * - Uses USAGE_ASSISTANT + CONTENT_TYPE_SPEECH (instead of low-volume earpiece USAGE_VOICE_COMMUNICATION)
 *   so speech routes cleanly at full clarity through Speaker, Bluetooth, and Wired Headsets.
 * - Applies configurable soft-limited PCM speech gain + optional safe hardware LoudnessEnhancer
 *   without touching system media volume or causing digital clipping.
 * - Bounded Channel(32, DROP_OLDEST) prevents memory growth under bursts.
 * - Uses atomic activeTurnId + synchronous pause/flush/drain for instant Stop & barge-in interruption
 *   so stale chunks from previous responses can NEVER play afterward.
 */
@Singleton
class AndroidVoiceAudioPlaybackDataSourceImpl @Inject constructor(
    private val dispatchers: DispatcherProvider
) : VoiceAudioPlaybackDataSource {

    companion object {
        const val OUTPUT_SAMPLE_RATE_HZ = 24_000
        private const val PLAYBACK_CHANNEL_CAPACITY = 32
        private const val SAFE_LOUDNESS_ENHANCER_MB = 350 // +3.5 dB conservative hardware lift
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val activeTurnId = AtomicLong(0L)
    private val muted = AtomicBoolean(false)
    private val trackLock = Any()
    private val gainProcessor = PcmSpeechGainProcessor(PcmSpeechGainProcessor.DEFAULT_SPEECH_GAIN)

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var playbackChannel: Channel<VoiceAudioChunk>? = null
    private var consumerJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _outputAmplitude = MutableStateFlow(0f)
    override val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

    /** Updates gain processing for future playback chunks. */
    override fun setPlaybackGain(gainMultiplier: Float) {
        gainProcessor.setGain(gainMultiplier)
    }

    /** Creates or reuses the streaming AudioTrack for the given response turn. */
    override fun startSession(turnId: Long): Result<Unit> {
        activeTurnId.set(turnId)
        synchronized(trackLock) {
            if (audioTrack != null && consumerJob?.isActive == true) {
                return Result.success(Unit)
            }
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                return Result.failure(IllegalStateException("Unsupported playback audio format"))
            }

            // USAGE_ASSISTANT routes through the media/assistant speaker path (full clarity)
            // instead of the attenuated STREAM_VOICE_CALL earpiece path.
            val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes.USAGE_ASSISTANT
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(OUTPUT_SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 16_384))
                    .build()
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("AudioTrack creation failed: ${e.message}", e))
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return Result.failure(IllegalStateException("AudioTrack failed to initialize"))
            }

            applyVolume(track)
            attachSafeLoudnessEnhancer(track.audioSessionId)
            audioTrack = track
            val channel = Channel<VoiceAudioChunk>(
                capacity = PLAYBACK_CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            playbackChannel = channel
            startConsumerLoop(track, channel)
            return Result.success(Unit)
        }
    }

    /** Adds a chunk to the bounded playback queue when it belongs to the active turn. */
    override fun enqueueChunk(chunk: VoiceAudioChunk): Boolean {
        if (chunk.pcmBytes.isEmpty() || chunk.pcmBytes.size % 2 != 0) {
            return false
        }
        if (chunk.turnId != activeTurnId.get()) {
            return false
        }
        val channel = playbackChannel ?: return false
        return channel.trySend(chunk).isSuccess
    }

    /** Stops current playback and discards buffered audio from the prior turn. */
    override fun stopImmediatelyAndFlush(newTurnId: Long) {
        activeTurnId.set(newTurnId)
        _isPlaying.value = false
        _outputAmplitude.value = 0f

        // Drain all queued stale audio frames immediately
        playbackChannel?.let { ch ->
            while (ch.tryReceive().isSuccess) {
                // discard stale chunk
            }
        }

        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                }
            }
        }
    }

    /** Mutes or unmutes the active output track. */
    override fun setMuted(muted: Boolean) {
        this.muted.set(muted)
        synchronized(trackLock) {
            audioTrack?.let(::applyVolume)
        }
    }

    /** Cancels playback and releases the track and its audio effects. */
    override fun release() {
        activeTurnId.incrementAndGet()
        _isPlaying.value = false
        _outputAmplitude.value = 0f
        synchronized(trackLock) {
            val trackToRelease = audioTrack
            audioTrack = null
            // Pause & flush before cancelling consumerJob/releasing so any blocking write unblocks cleanly
            trackToRelease?.let { track ->
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                    if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                        track.stop()
                    }
                }
            }
            consumerJob?.cancel()
            consumerJob = null
            playbackChannel?.close()
            playbackChannel = null
            runCatching { loudnessEnhancer?.release() }
            loudnessEnhancer = null
            trackToRelease?.let { track ->
                runCatching { track.release() }
            }
        }
    }

    /** Attaches a conservative hardware loudness enhancer when the device supports it. */
    private fun attachSafeLoudnessEnhancer(audioSessionId: Int) {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = runCatching {
            if (audioSessionId > 0) {
                LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(SAFE_LOUDNESS_ENHANCER_MB)
                    enabled = true
                }
            } else null
        }.getOrNull()
    }

    /** Writes queued PCM chunks to AudioTrack on the background playback scope. */
    private fun startConsumerLoop(track: AudioTrack, channel: Channel<VoiceAudioChunk>) {
        consumerJob?.cancel()
        consumerJob = scope.launch {
            try {
                for (chunk in channel) {
                    if (!isActive || audioTrack !== track) break
                    if (chunk.turnId != activeTurnId.get()) continue

                    val readyToWrite = synchronized(trackLock) {
                        if (audioTrack !== track || track.state != AudioTrack.STATE_INITIALIZED) {
                            false
                        } else {
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                runCatching { track.play() }
                            }
                            true
                        }
                    }
                    if (!readyToWrite) break

                    val processedPcm = gainProcessor.processPcm16Le(chunk.pcmBytes)
                    if (!isActive || audioTrack !== track || chunk.turnId != activeTurnId.get()) continue

                    _isPlaying.value = true
                    _outputAmplitude.value = if (muted.get()) 0f else computeNormalizedRms(processedPcm)

                    val written = runCatching {
                        if (audioTrack === track) {
                            track.write(processedPcm, 0, processedPcm.size, AudioTrack.WRITE_BLOCKING)
                        } else {
                            0
                        }
                    }.getOrElse { -1 }

                    if (written < 0 && audioTrack === track) {
                        runCatching { Log.w(TAG, "AudioTrack write error=$written") }
                    }

                    if (channel.isEmpty && audioTrack === track) {
                        _outputAmplitude.value = 0f
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                runCatching { Log.e(TAG, "AudioTrack playback loop failed", e) }
            } finally {
                if (audioTrack === track || audioTrack == null) {
                    _isPlaying.value = false
                    _outputAmplitude.value = 0f
                }
            }
        }
    }

    /** Applies current mute state and output gain to the track. */
    private fun applyVolume(track: AudioTrack) {
        val gain = if (muted.get()) 0f else AudioTrack.getMaxVolume()
        runCatching { track.setVolume(gain) }
    }

    /** Calculates normalized RMS amplitude for playback visualization. */
    private fun computeNormalizedRms(pcm16Le: ByteArray): Float {
        if (pcm16Le.size < 2) return 0f
        var sumSquares = 0.0
        val count = pcm16Le.size / 2
        var i = 0
        while (i + 1 < pcm16Le.size) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = ((hi shl 8) or lo) / 32768.0
            sumSquares += sample * sample
            i += 2
        }
        return (sqrt(sumSquares / count).toFloat() * 3.2f).coerceIn(0f, 1f)
    }
}
