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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.webrtc.resampler

import kotlinx.cinterop.*
import webrtc_resampler.*

// =========================================================================
// Native (cinterop) actual implementations
// =========================================================================

class NativeResampler(
    internal val ptr: CPointer<webrtc_resampler_t>,
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
        val result = webrtc_resampler_reset(
            ptr, inputSampleRate, outputSampleRate, channels.convert(),
        ) == 0
        if (result) {
            this.inputSampleRate = inputSampleRate
            this.outputSampleRate = outputSampleRate
            this.channels = channels
        }
        return result
    }

    override fun resetIfNeeded(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Boolean {
        val result = webrtc_resampler_reset_if_needed(
            ptr, inputSampleRate, outputSampleRate, channels.convert(),
        ) == 0
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
        return memScoped {
            val produced = alloc<UIntVar>()
            val result = input.usePinned { pinnedInput ->
                output.usePinned { pinnedOutput ->
                    webrtc_resampler_push(
                        ptr,
                        pinnedInput.addressOf(0),
                        input.size.convert(),
                        pinnedOutput.addressOf(0),
                        output.size.convert(),
                        produced.ptr,
                    )
                }
            }
            check(result == 0) {
                "webrtc::Resampler::Push rejected a block of ${input.size} samples at " +
                    "$inputSampleRate Hz -> $outputSampleRate Hz"
            }
            produced.value.toInt()
        }
    }
}

class NativePushSincResampler(
    internal val ptr: CPointer<webrtc_resampler_push_sinc_t>,
    override val sourceFrames: Int,
    override val destinationFrames: Int,
) : PushSincResampler {
    override fun close() {
        webrtc_resampler_push_sinc_destroy(ptr)
    }

    override fun resample(source: ShortArray, destination: ShortArray): Int {
        requirePushSincBuffers(source.size, destination.size, sourceFrames, destinationFrames)
        return source.usePinned { pinnedSource ->
            destination.usePinned { pinnedDestination ->
                webrtc_resampler_push_sinc_resample_s16(
                    ptr,
                    pinnedSource.addressOf(0),
                    source.size.convert(),
                    pinnedDestination.addressOf(0),
                    destination.size.convert(),
                ).toInt()
            }
        }
    }

    override fun resample(source: FloatArray, destination: FloatArray): Int {
        requirePushSincBuffers(source.size, destination.size, sourceFrames, destinationFrames)
        return source.usePinned { pinnedSource ->
            destination.usePinned { pinnedDestination ->
                webrtc_resampler_push_sinc_resample_f32(
                    ptr,
                    pinnedSource.addressOf(0),
                    source.size.convert(),
                    pinnedDestination.addressOf(0),
                    destination.size.convert(),
                ).toInt()
            }
        }
    }
}

class NativePushResamplerInt16(
    internal val ptr: CPointer<webrtc_resampler_push_s16_t>,
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
        input.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                webrtc_resampler_push_s16_resample(
                    ptr, pinnedInput.addressOf(0), pinnedOutput.addressOf(0),
                )
            }
        }
    }
}

class NativePushResamplerFloat(
    internal val ptr: CPointer<webrtc_resampler_push_f32_t>,
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
        input.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                webrtc_resampler_push_f32_resample(
                    ptr, pinnedInput.addressOf(0), pinnedOutput.addressOf(0),
                )
            }
        }
    }
}

class NativeSincResampler(
    internal val ptr: CPointer<webrtc_resampler_sinc_t>,
    private val sourceRef: StableRef<ResamplerSource>,
    override val requestFrames: Int,
) : SincResampler {
    override val chunkSize: Int
        get() = webrtc_resampler_sinc_chunk_size(ptr).toInt()

    override fun close() {
        webrtc_resampler_sinc_destroy(ptr)
        sourceRef.dispose()
    }

    override fun resample(frames: Int, destination: FloatArray) {
        require(frames >= 0) { "frames must not be negative, was $frames" }
        require(destination.size >= frames) {
            "destination holds ${destination.size} samples, $frames are needed"
        }
        if (frames == 0) {
            return
        }
        destination.usePinned { pinnedDestination ->
            webrtc_resampler_sinc_resample(
                ptr, frames.convert(), pinnedDestination.addressOf(0),
            )
        }
    }

    override fun flush() {
        webrtc_resampler_sinc_flush(ptr)
    }

    override fun setRatio(ioSampleRateRatio: Double) {
        webrtc_resampler_sinc_set_ratio(ptr, ioSampleRateRatio)
    }
}

// =========================================================================
// Pull callback
//
// cinterop requires a C function pointer, so the Kotlin source travels through
// a StableRef; the callback runs on the thread that calls resample().
// =========================================================================

private val sincReadCallback: webrtc_resampler_read_callback = staticCFunction { userData, frames, destination ->
    val source = userData!!.asStableRef<ResamplerSource>().get()
    val count = frames.toInt()
    val buffer = FloatArray(count)
    source.read(count, buffer)
    val dest = destination!!
    for (i in 0 until count) {
        dest[i] = buffer[i]
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createResampler(): Resampler {
    val ptr = webrtc_resampler_create() ?: error("webrtc_resampler_create returned null")
    return NativeResampler(ptr)
}

actual fun createResampler(
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int
): Resampler {
    val ptr = webrtc_resampler_create_reset(
        inputSampleRate, outputSampleRate, channels.convert(),
    ) ?: throw IllegalArgumentException(
        "unsupported rate pair $inputSampleRate Hz -> $outputSampleRate Hz with " +
            "$channels channel(s); webrtc::Resampler only handles the fixed WebRTC " +
            "ratios - use createPushSincResampler for arbitrary ratios",
    )
    return NativeResampler(ptr, inputSampleRate, outputSampleRate, channels)
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
    val sourceRef = StableRef.create(source)
    val ptr = webrtc_resampler_sinc_create(
        ioSampleRateRatio, requestFrames.convert(), sincReadCallback, sourceRef.asCPointer(),
    )
    if (ptr == null) {
        sourceRef.dispose()
        error("webrtc_resampler_sinc_create returned null")
    }
    return NativeSincResampler(ptr, sourceRef, requestFrames)
}

actual fun createPushSincResampler(
    sourceFrames: Int,
    destinationFrames: Int
): PushSincResampler {
    val ptr = webrtc_resampler_push_sinc_create(
        sourceFrames.convert(), destinationFrames.convert(),
    ) ?: error("webrtc_resampler_push_sinc_create returned null")
    return NativePushSincResampler(ptr, sourceFrames, destinationFrames)
}

actual fun createPushResamplerInt16(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerInt16 {
    val ptr = webrtc_resampler_push_s16_create(
        sourceFramesPerChannel.convert(), destinationFramesPerChannel.convert(), channels.convert(),
    ) ?: error("webrtc_resampler_push_s16_create returned null")
    return NativePushResamplerInt16(
        ptr, sourceFramesPerChannel, destinationFramesPerChannel, channels,
    )
}

actual fun createPushResamplerFloat(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int
): PushResamplerFloat {
    val ptr = webrtc_resampler_push_f32_create(
        sourceFramesPerChannel.convert(), destinationFramesPerChannel.convert(), channels.convert(),
    ) ?: error("webrtc_resampler_push_f32_create returned null")
    return NativePushResamplerFloat(
        ptr, sourceFramesPerChannel, destinationFramesPerChannel, channels,
    )
}

actual fun resamplerVersion(): String =
    webrtc_resampler_version()?.toKString() ?: ""

actual fun sincResamplerKernelSize(): Int = webrtc_resampler_sinc_kernel_size().toInt()

actual fun pushSincAlgorithmicDelaySeconds(sourceRateHz: Int): Float =
    webrtc_resampler_push_sinc_algorithmic_delay_seconds(sourceRateHz)
