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
// JNI bridge – loads the native library and provides external declarations
// =========================================================================

internal object Jni {
    init {
        NativeLoader.load()
    }

    external fun version(): String

    // ---- webrtc::Resampler ----
    external fun resamplerCreate(): Long
    external fun resamplerCreateReset(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Long
    external fun resamplerDestroy(ptr: Long)
    external fun resamplerReset(ptr: Long, inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean
    external fun resamplerResetIfNeeded(ptr: Long, inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean
    external fun resamplerPush(ptr: Long, input: ShortArray, output: ShortArray): Int

    // ---- webrtc::PushSincResampler ----
    external fun pushSincCreate(sourceFrames: Int, destinationFrames: Int): Long
    external fun pushSincDestroy(ptr: Long)
    external fun pushSincResampleS16(ptr: Long, source: ShortArray, destination: ShortArray): Int
    external fun pushSincResampleF32(ptr: Long, source: FloatArray, destination: FloatArray): Int
    external fun pushSincAlgorithmicDelaySeconds(sourceRateHz: Int): Float

    // ---- webrtc::PushResampler<int16_t> ----
    external fun pushS16Create(
        sourceFramesPerChannel: Int,
        destinationFramesPerChannel: Int,
        channels: Int
    ): Long

    external fun pushS16Destroy(ptr: Long)
    external fun pushS16Resample(ptr: Long, input: ShortArray, output: ShortArray)

    // ---- webrtc::PushResampler<float> ----
    external fun pushF32Create(
        sourceFramesPerChannel: Int,
        destinationFramesPerChannel: Int,
        channels: Int
    ): Long

    external fun pushF32Destroy(ptr: Long)
    external fun pushF32Resample(ptr: Long, input: FloatArray, output: FloatArray)

    // ---- webrtc::SincResampler ----
    external fun sincCreate(ioSampleRateRatio: Double, requestFrames: Int, source: ResamplerSource): Long
    external fun sincDestroy(ptr: Long)
    external fun sincResample(ptr: Long, frames: Int, destination: FloatArray)
    external fun sincChunkSize(ptr: Long): Int
    external fun sincKernelSize(): Int
    external fun sincRequestFrames(ptr: Long): Int
    external fun sincFlush(ptr: Long)
    external fun sincSetRatio(ptr: Long, ioSampleRateRatio: Double)
}

// =========================================================================
// JVM/Android actual implementations
// =========================================================================

class JvmResampler(
    internal val ptr: Long,
    inputSampleRate: Int = 0,
    outputSampleRate: Int = 0,
    channels: Int = 0,
) : Resampler {
    override var inputSampleRate: Int = inputSampleRate
        private set

    override var outputSampleRate: Int = outputSampleRate
        private set

    override var channels: Int = channels
        private set

    override fun close() {
        Jni.resamplerDestroy(ptr)
    }

    override fun reset(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean {
        val result = Jni.resamplerReset(ptr, inputSampleRate, outputSampleRate, channels)
        if (result) {
            this.inputSampleRate = inputSampleRate
            this.outputSampleRate = outputSampleRate
            this.channels = channels
        }
        return result
    }

    override fun resetIfNeeded(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean {
        val result = Jni.resamplerResetIfNeeded(ptr, inputSampleRate, outputSampleRate, channels)
        if (result) {
            this.inputSampleRate = inputSampleRate
            this.outputSampleRate = outputSampleRate
            this.channels = channels
        }
        return result
    }

    override fun resample(input: ShortArray, output: ShortArray): Int {
        check(inputSampleRate != 0 && outputSampleRate != 0) {
            "resample() called before reset(); the resampler has no rate pair"
        }
        requireResamplerBuffers(input, output, inputSampleRate, outputSampleRate)
        val produced = Jni.resamplerPush(ptr, input, output)
        check(produced >= 0) {
            "webrtc::Resampler::Push rejected a block of ${input.size} samples at " +
                "$inputSampleRate Hz -> $outputSampleRate Hz"
        }
        return produced
    }
}

class JvmPushSincResampler(
    internal val ptr: Long,
    override val sourceFrames: Int,
    override val destinationFrames: Int,
) : PushSincResampler {
    override fun close() {
        Jni.pushSincDestroy(ptr)
    }

    override fun resample(source: ShortArray, destination: ShortArray): Int {
        requirePushSincBuffers(source.size, destination.size, sourceFrames, destinationFrames)
        return Jni.pushSincResampleS16(ptr, source, destination)
    }

    override fun resample(source: FloatArray, destination: FloatArray): Int {
        requirePushSincBuffers(source.size, destination.size, sourceFrames, destinationFrames)
        return Jni.pushSincResampleF32(ptr, source, destination)
    }
}

class JvmPushResamplerInt16(
    internal val ptr: Long,
    override val sourceFramesPerChannel: Int,
    override val destinationFramesPerChannel: Int,
    override val channels: Int,
) : PushResamplerInt16 {
    override fun close() {
        Jni.pushS16Destroy(ptr)
    }

    override fun resample(input: ShortArray, output: ShortArray) {
        requirePushBuffers(
            input.size, output.size,
            sourceFramesPerChannel, destinationFramesPerChannel, channels,
        )
        Jni.pushS16Resample(ptr, input, output)
    }
}

class JvmPushResamplerFloat(
    internal val ptr: Long,
    override val sourceFramesPerChannel: Int,
    override val destinationFramesPerChannel: Int,
    override val channels: Int,
) : PushResamplerFloat {
    override fun close() {
        Jni.pushF32Destroy(ptr)
    }

    override fun resample(input: FloatArray, output: FloatArray) {
        requirePushBuffers(
            input.size, output.size,
            sourceFramesPerChannel, destinationFramesPerChannel, channels,
        )
        Jni.pushF32Resample(ptr, input, output)
    }
}

class JvmSincResampler(
    internal val ptr: Long,
    override val requestFrames: Int,
) : SincResampler {
    override val chunkSize: Int
        get() = Jni.sincChunkSize(ptr)

    override fun close() {
        Jni.sincDestroy(ptr)
    }

    override fun resample(frames: Int, destination: FloatArray) {
        require(frames >= 0) { "frames must not be negative, was $frames" }
        require(destination.size >= frames) {
            "destination holds ${destination.size} samples, $frames are needed"
        }
        Jni.sincResample(ptr, frames, destination)
    }

    override fun flush() {
        Jni.sincFlush(ptr)
    }

    override fun setRatio(ioSampleRateRatio: Double) {
        Jni.sincSetRatio(ptr, ioSampleRateRatio)
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createResampler(): Resampler = JvmResampler(Jni.resamplerCreate())

actual fun createResampler(
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int
): Resampler {
    val ptr = Jni.resamplerCreateReset(inputSampleRate, outputSampleRate, channels)
    require(ptr != 0L) {
        "unsupported rate pair $inputSampleRate Hz -> $outputSampleRate Hz with " +
            "$channels channel(s); webrtc::Resampler only handles the fixed WebRTC " +
            "ratios - use createPushSincResampler for arbitrary ratios"
    }
    return JvmResampler(ptr, inputSampleRate, outputSampleRate, channels)
}

actual fun createSincResampler(
    ioSampleRateRatio: Double,
    requestFrames: Int,
    source: ResamplerSource
): SincResampler {
    val kernelSize = sincResamplerKernelSize()
    require(requestFrames > kernelSize) {
        "requestFrames must be greater than the kernel size ($kernelSize), was $requestFrames"
    }
    val ptr = Jni.sincCreate(ioSampleRateRatio, requestFrames, source)
    check(ptr != 0L) { "failed to create the SincResampler" }
    return JvmSincResampler(ptr, requestFrames)
}

actual fun createPushSincResampler(
    sourceFrames: Int,
    destinationFrames: Int
): PushSincResampler {
    val ptr = Jni.pushSincCreate(sourceFrames, destinationFrames)
    check(ptr != 0L) { "failed to create the PushSincResampler" }
    return JvmPushSincResampler(ptr, sourceFrames, destinationFrames)
}

actual fun createPushResamplerInt16(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerInt16 {
    val ptr = Jni.pushS16Create(sourceFramesPerChannel, destinationFramesPerChannel, channels)
    check(ptr != 0L) { "failed to create the PushResampler<int16_t>" }
    return JvmPushResamplerInt16(
        ptr, sourceFramesPerChannel, destinationFramesPerChannel, channels,
    )
}

actual fun createPushResamplerFloat(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerFloat {
    val ptr = Jni.pushF32Create(sourceFramesPerChannel, destinationFramesPerChannel, channels)
    check(ptr != 0L) { "failed to create the PushResampler<float>" }
    return JvmPushResamplerFloat(
        ptr, sourceFramesPerChannel, destinationFramesPerChannel, channels,
    )
}

actual fun resamplerVersion(): String = Jni.version()

actual fun sincResamplerKernelSize(): Int = Jni.sincKernelSize()

actual fun pushSincAlgorithmicDelaySeconds(sourceRateHz: Int): Float =
    Jni.pushSincAlgorithmicDelaySeconds(sourceRateHz)
