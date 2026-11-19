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

import org.khronos.webgl.toFloat32Array
import org.khronos.webgl.toFloatArray
import org.khronos.webgl.toInt16Array
import org.khronos.webgl.toShortArray
import kotlin.math.roundToInt

// =========================================================================
// wasmJs actual implementations
//
// Each class is a handle into the Emscripten module: sample buffers are copied
// into the module's heap for the call and read back afterwards (see
// WasmBridge.kt for the declarations and wasm/webrtc_resampler_kmp_glue.js for
// the JS side).
// =========================================================================

class WasmResampler(
    internal val ptr: Int,
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
        webrtc_resampler_destroy(ptr)
    }

    override fun reset(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean {
        val result = webrtc_resampler_reset(ptr, inputSampleRate, outputSampleRate, channels) == 0
        if (result) {
            this.inputSampleRate = inputSampleRate
            this.outputSampleRate = outputSampleRate
            this.channels = channels
        }
        return result
    }

    override fun resetIfNeeded(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean {
        val result = webrtc_resampler_reset_if_needed(ptr, inputSampleRate, outputSampleRate, channels) == 0
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
        return withHeap(input.size * Short.SIZE_BYTES) { inputPtr ->
            webrtcResamplerKmpWriteS16(inputPtr, input.toInt16Array())
            withHeap(output.size * Short.SIZE_BYTES) { outputPtr ->
                val produced = webrtc_resampler_push(ptr, inputPtr, input.size, outputPtr, output.size)
                check(produced >= 0) {
                    "webrtc::Resampler::Push rejected a block of ${input.size} samples at " +
                        "$inputSampleRate Hz -> $outputSampleRate Hz"
                }
                webrtcResamplerKmpReadS16(outputPtr, produced).toShortArray().copyInto(output)
                produced
            }
        }
    }
}

class WasmPushSincResampler(
    internal val ptr: Int,
    override val sourceFrames: Int,
    override val destinationFrames: Int,
) : PushSincResampler {
    override fun close() {
        webrtc_resampler_push_sinc_destroy(ptr)
    }

    override fun resample(source: ShortArray, destination: ShortArray): Int {
        requirePushSincBuffers(source.size, destination.size, sourceFrames, destinationFrames)
        return withHeap(source.size * Short.SIZE_BYTES) { sourcePtr ->
            webrtcResamplerKmpWriteS16(sourcePtr, source.toInt16Array())
            withHeap(destination.size * Short.SIZE_BYTES) { destinationPtr ->
                val written = webrtc_resampler_push_sinc_resample_s16(
                    ptr, sourcePtr, source.size, destinationPtr, destination.size,
                )
                webrtcResamplerKmpReadS16(destinationPtr, written).toShortArray().copyInto(destination)
                written
            }
        }
    }

    override fun resample(source: FloatArray, destination: FloatArray): Int {
        requirePushSincBuffers(source.size, destination.size, sourceFrames, destinationFrames)
        return withHeap(source.size * Float.SIZE_BYTES) { sourcePtr ->
            webrtcResamplerKmpWriteF32(sourcePtr, source.toFloat32Array())
            withHeap(destination.size * Float.SIZE_BYTES) { destinationPtr ->
                val written = webrtc_resampler_push_sinc_resample_f32(
                    ptr, sourcePtr, source.size, destinationPtr, destination.size,
                )
                webrtcResamplerKmpReadF32(destinationPtr, written).toFloatArray().copyInto(destination)
                written
            }
        }
    }
}

class WasmPushResamplerInt16(
    internal val ptr: Int,
    override val sourceFramesPerChannel: Int,
    override val destinationFramesPerChannel: Int,
    override val channels: Int,
) : PushResamplerInt16 {
    override fun close() {
        webrtc_resampler_push_s16_destroy(ptr)
    }

    override fun resample(input: ShortArray, output: ShortArray) {
        requirePushBuffers(
            input.size, output.size,
            sourceFramesPerChannel, destinationFramesPerChannel, channels,
        )
        withHeap(input.size * Short.SIZE_BYTES) { inputPtr ->
            webrtcResamplerKmpWriteS16(inputPtr, input.toInt16Array())
            withHeap(output.size * Short.SIZE_BYTES) { outputPtr ->
                webrtc_resampler_push_s16_resample(ptr, inputPtr, outputPtr)
                webrtcResamplerKmpReadS16(outputPtr, output.size).toShortArray().copyInto(output)
            }
        }
    }
}

class WasmPushResamplerFloat(
    internal val ptr: Int,
    override val sourceFramesPerChannel: Int,
    override val destinationFramesPerChannel: Int,
    override val channels: Int,
) : PushResamplerFloat {
    override fun close() {
        webrtc_resampler_push_f32_destroy(ptr)
    }

    override fun resample(input: FloatArray, output: FloatArray) {
        requirePushBuffers(
            input.size, output.size,
            sourceFramesPerChannel, destinationFramesPerChannel, channels,
        )
        withHeap(input.size * Float.SIZE_BYTES) { inputPtr ->
            webrtcResamplerKmpWriteF32(inputPtr, input.toFloat32Array())
            withHeap(output.size * Float.SIZE_BYTES) { outputPtr ->
                webrtc_resampler_push_f32_resample(ptr, inputPtr, outputPtr)
                webrtcResamplerKmpReadF32(outputPtr, output.size).toFloatArray().copyInto(output)
            }
        }
    }
}

/**
 * `webrtc::SincResampler` has a pull interface, which needs a C callback that
 * Kotlin/Wasm cannot provide (a Kotlin lambda is not a JS function, and the
 * module has no way to call back into Kotlin). This runs the same algorithm
 * through the library's push interface instead: one [requestFrames] block is
 * pulled from [source] at a time and pushed through a [PushSincResampler]
 * whose destination block is the matching number of output frames, so a caller
 * that asks for fewer or more frames than one block gets a continuous stream.
 */
class WasmSincResampler(
    private val ratio: Double,
    override val requestFrames: Int,
    private val source: ResamplerSource,
) : SincResampler {
    private var currentRatio = ratio

    private var delegate: WasmPushSincResampler = newDelegate()

    private val sourceBlock = FloatArray(requestFrames)

    private var outputBlock = FloatArray(destinationFramesFor(currentRatio))

    /** Frames filled in [outputBlock] by the last push. */
    private var blockSize = 0

    /** Frames of [outputBlock] the caller has already received. */
    private var blockOffset = 0

    override val chunkSize: Int
        get() = ((requestFrames - sincResamplerKernelSize() / 2) / currentRatio).toInt().coerceAtLeast(1)

    override fun close() {
        delegate.close()
    }

    override fun resample(frames: Int, destination: FloatArray) {
        require(frames >= 0) { "frames must not be negative, was was $frames" }
        require(destination.size >= frames) {
            "destination holds ${destination.size} samples, $frames are needed"
        }
        var produced = 0
        while (produced < frames) {
            if (blockOffset >= blockSize) {
                source.read(requestFrames, sourceBlock)
                blockSize = delegate.resample(sourceBlock, outputBlock)
                blockOffset = 0
            }
            val take = minOf(frames - produced, blockSize - blockOffset)
            outputBlock.copyInto(destination, produced, blockOffset, blockOffset + take)
            blockOffset += take
            produced += take
        }
    }

    override fun flush() {
        delegate.close()
        delegate = newDelegate()
        blockSize = 0
        blockOffset = 0
    }

    override fun setRatio(ioSampleRateRatio: Double) {
        if (ioSampleRateRatio == currentRatio) return
        currentRatio = ioSampleRateRatio
        outputBlock = FloatArray(destinationFramesFor(currentRatio))
        delegate.close()
        delegate = newDelegate()
        blockSize = 0
        blockOffset = 0
    }

    private fun destinationFramesFor(ratio: Double): Int =
        maxOf(1, (requestFrames / ratio).roundToInt())

    private fun newDelegate(): WasmPushSincResampler {
        val destinationFrames = destinationFramesFor(currentRatio)
        val handle = webrtc_resampler_push_sinc_create(requestFrames, destinationFrames)
        check(handle != 0) { "failed to create the PushSincResampler behind the SincResampler" }
        return WasmPushSincResampler(handle, requestFrames, destinationFrames)
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createResampler(): Resampler {
    requireWasmModule()
    return WasmResampler(webrtc_resampler_create())
}

actual fun createResampler(
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int
): Resampler {
    requireWasmModule()
    val ptr = webrtc_resampler_create_reset(inputSampleRate, outputSampleRate, channels)
    require(ptr != 0) {
        "unsupported rate pair $inputSampleRate Hz -> $outputSampleRate Hz with " +
            "$channels channel(s); webrtc::Resampler only handles the fixed WebRTC " +
            "ratios - use createPushSincResampler for arbitrary ratios"
    }
    return WasmResampler(ptr, inputSampleRate, outputSampleRate, channels)
}

actual fun createSincResampler(
    ioSampleRateRatio: Double,
    requestFrames: Int,
    source: ResamplerSource
): SincResampler {
    requireWasmModule()
    val kernelSize = sincResamplerKernelSize()
    require(requestFrames > kernelSize) {
        "requestFrames must be greater than the kernel size ($kernelSize), was $requestFrames"
    }
    return WasmSincResampler(ioSampleRateRatio, requestFrames, source)
}

actual fun createPushSincResampler(
    sourceFrames: Int,
    destinationFrames: Int
): PushSincResampler {
    requireWasmModule()
    val ptr = webrtc_resampler_push_sinc_create(sourceFrames, destinationFrames)
    check(ptr != 0) { "failed to create the PushSincResampler" }
    return WasmPushSincResampler(ptr, sourceFrames, destinationFrames)
}

actual fun createPushResamplerInt16(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerInt16 {
    requireWasmModule()
    val ptr = webrtc_resampler_push_s16_create(sourceFramesPerChannel, destinationFramesPerChannel, channels)
    check(ptr != 0) { "failed to create the PushResampler<int16_t>" }
    return WasmPushResamplerInt16(ptr, sourceFramesPerChannel, destinationFramesPerChannel, channels)
}

actual fun createPushResamplerFloat(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerFloat {
    requireWasmModule()
    val ptr = webrtc_resampler_push_f32_create(sourceFramesPerChannel, destinationFramesPerChannel, channels)
    check(ptr != 0) { "failed to create the PushResampler<float>" }
    return WasmPushResamplerFloat(ptr, sourceFramesPerChannel, destinationFramesPerChannel, channels)
}

actual fun resamplerVersion(): String {
    requireWasmModule()
    return webrtc_resampler_version()
}

actual fun sincResamplerKernelSize(): Int = webrtc_resampler_sinc_kernel_size()

actual fun pushSincAlgorithmicDelaySeconds(sourceRateHz: Int): Float =
    webrtc_resampler_push_sinc_algorithmic_delay_seconds(sourceRateHz)
