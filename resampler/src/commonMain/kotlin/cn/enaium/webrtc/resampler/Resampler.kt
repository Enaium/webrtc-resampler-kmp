/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.webrtc.resampler

// =========================================================================
// Top-level expect factory functions
// =========================================================================

/**
 * Creates a multi-rate resampler that is not configured yet; call [Resampler.reset]
 * before the first [Resampler.resample].
 */
expect fun createResampler(): Resampler

/**
 * Creates a multi-rate resampler for [inputSampleRate] -> [outputSampleRate].
 *
 * @throws IllegalArgumentException if the rate pair or the channel count is not
 * supported (see [Resampler]).
 */
expect fun createResampler(
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int
): Resampler

/**
 * Creates the pull-based windowed sinc resampler.
 *
 * @param ioSampleRateRatio input / output sample rate, e.g. `48000.0 / 16000.0`.
 * @param requestFrames frames requested from [source] per read; must be greater
 * than 32.
 */
expect fun createSincResampler(
    ioSampleRateRatio: Double,
    requestFrames: Int,
    source: ResamplerSource
): SincResampler

/**
 * Creates the mono push resampler.
 *
 * @param sourceFrames and [destinationFrames] are the sizes of one block of the
 * same duration (typically 10 ms), which is what fixes the ratio.
 */
expect fun createPushSincResampler(sourceFrames: Int, destinationFrames: Int): PushSincResampler

/**
 * Creates the multichannel int16 push resampler.
 *
 * @param sourceFramesPerChannel and [destinationFramesPerChannel] are the
 * per-channel sizes of one block of the same duration (at most 10 ms).
 */
expect fun createPushResamplerInt16(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerInt16

/** Creates the multichannel float push resampler. */
expect fun createPushResamplerFloat(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerFloat

/** Version of the wrapped webrtc-resampler library. */
expect fun resamplerVersion(): String

/**
 * Kernel size of the sinc resampler; the `requestFrames` of
 * [createSincResampler] must be greater than this.
 */
expect fun sincResamplerKernelSize(): Int

/**
 * Delay due to the filter kernel of [PushSincResampler], i.e. the time after
 * which an input sample appears in the resampled output.
 */
expect fun pushSincAlgorithmicDelaySeconds(sourceRateHz: Int): Float

// =========================================================================
// Common interfaces
// =========================================================================

/**
 * `webrtc::Resampler`: the integer multi-rate resampler, `int16` interleaved.
 *
 * It covers the fixed rate pairs WebRTC supports - 1:1, 1:2, 1:3, 1:4, 1:6,
 * 1:12, 2:3, 2:11, 4:11, 8:11, 11:16, 11:32 and their inverses, i.e. the
 * 8/16/32/44/48/96 kHz combinations, but neither 44 kHz <-> 48/96 kHz nor any
 * 44.1 or 22.05 kHz ratio. One or two channels.
 *
 * For arbitrary ratios (44.1 kHz <-> 48 kHz and the like), or for float
 * samples, use [PushSincResampler] or [PushResamplerInt16] instead.
 */
interface Resampler : AutoCloseable {
    /** Input rate the resampler is currently configured for. */
    val inputSampleRate: Int

    /** Output rate the resampler is currently configured for. */
    val outputSampleRate: Int

    /** Channel count the resampler is currently configured for. */
    val channels: Int

    /**
     * Reconfigures the resampler and resets all states.
     *
     * @return `true` on success, `false` when the rate pair or channel count is
     * not supported.
     */
    fun reset(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean

    /**
     * Same as [reset], but keeps the current states when nothing changed.
     *
     * @return `true` on success, `false` when the rate pair or channel count is
     * not supported.
     */
    fun resetIfNeeded(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean

    /**
     * Resamples [input] into [output].
     *
     * [input] and [output] hold interleaved samples: a 10 ms block at 48 kHz is
     * `480 * channels` samples long, and resampling it to 16 kHz produces
     * `160 * channels` samples.
     *
     * @return the number of samples written to [output].
     * @throws IllegalStateException when the block size is not one the
     * configured mode supports (for example a 1:3 conversion only takes blocks
     * that are a multiple of 160 samples per channel).
     */
    fun resample(input: ShortArray, output: ShortArray): Int
}

/** Supplies samples to a [SincResampler] whenever it needs more input. */
fun interface ResamplerSource {
    /**
     * Fills [destination] with its `frames` samples, zero padding when fewer
     * frames are available. Called on the thread that calls
     * [SincResampler.resample].
     */
    fun read(frames: Int, destination: FloatArray)
}

/**
 * `webrtc::SincResampler`: high quality single channel windowed sinc resampler
 * with a pull interface, for arbitrary ratios.
 */
interface SincResampler : AutoCloseable {
    /** Frames requested from the [ResamplerSource] per read. */
    val requestFrames: Int

    /** Maximum number of frames that guarantees a single source read per [resample]. */
    val chunkSize: Int

    /**
     * Resamples [frames] samples pulled from the [ResamplerSource] into
     * [destination]. At most `destination.size` frames are written.
     */
    fun resample(frames: Int, destination: FloatArray)

    /** Flushes all buffered data and resets the internal indices. */
    fun flush()

    /**
     * Updates the input / output ratio, e.g. `48000.0 / 16000.0`; rebuilds the
     * resampling kernels.
     */
    fun setRatio(ioSampleRateRatio: Double)
}

/**
 * `webrtc::PushSincResampler`: single channel windowed sinc resampler with a
 * push interface, for arbitrary ratios. One block in, one block out.
 */
interface PushSincResampler : AutoCloseable {
    /** Block size in samples the resampler was created with. */
    val sourceFrames: Int

    /** Block size in samples this resampler produces per [resample]. */
    val destinationFrames: Int

    /**
     * Resamples one block of `int16` samples; [source] must hold exactly
     * [sourceFrames] samples and [destination] at least [destinationFrames].
     *
     * @return the number of samples written to [destination].
     */
    fun resample(source: ShortArray, destination: ShortArray): Int

    /**
     * Resamples one block of float samples; [source] must hold exactly
     * [sourceFrames] samples and [destination] at least [destinationFrames].
     *
     * @return the number of samples written to [destination].
     */
    fun resample(source: FloatArray, destination: FloatArray): Int
}

/**
 * `webrtc::PushResampler<int16_t>`: multichannel (at most 8 channels) windowed
 * sinc resampler over interleaved `int16` samples.
 */
interface PushResamplerInt16 : AutoCloseable {
    /** Per-channel block size in samples the resampler was created with. */
    val sourceFramesPerChannel: Int

    /** Per-channel block size in samples this resampler produces per [resample]. */
    val destinationFramesPerChannel: Int

    /** Channel count the resampler was created with. */
    val channels: Int

    /**
     * Resamples one interleaved block; [input] must hold
     * `sourceFramesPerChannel * channels` samples and [output] at least
     * `destinationFramesPerChannel * channels`.
     */
    fun resample(input: ShortArray, output: ShortArray)
}

/** `webrtc::PushResampler<float>`: like [PushResamplerInt16], over float samples. */
interface PushResamplerFloat : AutoCloseable {
    /** Per-channel block size in samples the resampler was created with. */
    val sourceFramesPerChannel: Int

    /** Per-channel block size in samples this resampler produces per [resample]. */
    val destinationFramesPerChannel: Int

    /** Channel count the resampler was created with. */
    val channels: Int

    /**
     * Resamples one interleaved block; [input] must hold
     * `sourceFramesPerChannel * channels` samples and [output] at least
     * `destinationFramesPerChannel * channels`.
     */
    fun resample(input: FloatArray, output: FloatArray)
}

// =========================================================================
// Shared argument checks
//
// The C++ classes assert these with RTC_CHECK, which aborts the process; every
// entry point validates first so a caller mistake surfaces as an exception.
// =========================================================================

/** Number of samples a ratio produces for [inputSamples] interleaved samples. */
internal fun outputSamplesFor(inputSamples: Int, inputRate: Int, outputRate: Int): Int =
    ((inputSamples.toLong() * outputRate + inputRate - 1) / inputRate).toInt()

internal fun requireResamplerBuffers(
    input: ShortArray,
    output: ShortArray,
    inputRate: Int,
    outputRate: Int,
) {
    val needed = outputSamplesFor(input.size, inputRate, outputRate)
    require(output.size >= needed) {
        "output holds ${output.size} samples, $needed are needed for ${input.size} " +
            "samples at $inputRate Hz -> $outputRate Hz"
    }
}

internal fun requirePushSincBuffers(
    source: Int,
    destination: Int,
    sourceFrames: Int,
    destinationFrames: Int,
) {
    require(source == sourceFrames) {
        "source holds $source samples, this resampler takes blocks of $sourceFrames"
    }
    require(destination >= destinationFrames) {
        "destination holds $destination samples, this resampler needs $destinationFrames"
    }
}

internal fun requirePushBuffers(
    source: Int,
    destination: Int,
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int,
) {
    val expectedSource = sourceFramesPerChannel * channels
    val expectedDestination = destinationFramesPerChannel * channels
    require(source == expectedSource) {
        "input holds $source samples, this resampler takes $expectedSource " +
            "($sourceFramesPerChannel samples per channel, $channels channels)"
    }
    require(destination >= expectedDestination) {
        "output holds $destination samples, this resampler needs $expectedDestination"
    }
}
