package com.foodflow.data.voice

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Pure 16-bit little-endian PCM speech gain and soft-clipping limiter.
 * Boosts quiet Gemini Live conversational PCM headroom (~-16 dBFS) to clearly audible
 * speech loudness without modifying system media volume or introducing harsh digital clipping.
 */
class PcmSpeechGainProcessor(
    initialGainMultiplier: Float = DEFAULT_SPEECH_GAIN
) {
    companion object {
        const val MIN_GAIN = 1.0f
        const val MAX_GAIN = 3.0f
        const val DEFAULT_SPEECH_GAIN = 2.25f // ~+7.0 dB clean speech lift
        private const val SOFT_LIMIT_KNEE = 0.80f
        private const val CEILING = 0.96f
    }

    @Volatile
    private var gainMultiplier: Float = initialGainMultiplier.coerceIn(MIN_GAIN, MAX_GAIN)

    /** Updates the bounded gain applied to subsequent PCM buffers. */
    fun setGain(multiplier: Float) {
        gainMultiplier = multiplier.coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /** Returns the gain currently applied to PCM audio. */
    fun getGain(): Float = gainMultiplier

    /**
     * Processes 16-bit signed little-endian PCM bytes into a newly allocated gain-adjusted buffer.
     * Uses a smooth hyperbolic-tangent soft limiter above the knee to guarantee zero wrap-around
     * clipping distortion even on loud peaks.
     */
    fun processPcm16Le(input: ByteArray): ByteArray {
        val gain = gainMultiplier
        if (input.size < 2 || gain <= 1.001f) {
            return input
        }
        val evenLen = input.size and 1.inv()
        val output = ByteArray(evenLen)
        var i = 0
        while (i < evenLen) {
            val lo = input[i].toInt() and 0xFF
            val hi = input[i + 1].toInt()
            val rawSample = (hi shl 8) or lo
            val normalized = rawSample / 32768.0f
            val boosted = normalized * gain
            val limited = softLimit(boosted)
            val pcmOut = (limited * 32767.0f).toInt().coerceIn(-32768, 32767)
            output[i] = (pcmOut and 0xFF).toByte()
            output[i + 1] = ((pcmOut shr 8) and 0xFF).toByte()
            i += 2
        }
        return output
    }

    /** Compresses loud samples smoothly to avoid digital clipping. */
    private fun softLimit(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= SOFT_LIMIT_KNEE) return x
        val sign = if (x >= 0f) 1f else -1f
        val excess = magnitude - SOFT_LIMIT_KNEE
        val headroom = CEILING - SOFT_LIMIT_KNEE
        val compressed = SOFT_LIMIT_KNEE + headroom * tanh(excess / headroom)
        return sign * compressed.coerceAtMost(CEILING)
    }
}
