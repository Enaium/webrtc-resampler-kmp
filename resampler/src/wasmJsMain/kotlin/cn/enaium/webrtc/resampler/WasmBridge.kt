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

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int16Array

/*
 * Declarations for the JS glue (wasm/webrtc_resampler_kmp_glue.js, shipped in
 * the @enaium/webrtc-resampler-kmp npm package) that wraps the
 * Emscripten-compiled resampler module.
 *
 * The host page imports initWebrtcResamplerKmp() from that package and awaits it
 * before the Kotlin/Wasm module runs, so these globals exist by then.
 */

// ---- heap access ----

internal external fun webrtcResamplerKmpMalloc(size: Int): Int
internal external fun webrtcResamplerKmpFree(ptr: Int)
internal external fun webrtcResamplerKmpWriteS16(ptr: Int, data: Int16Array)
internal external fun webrtcResamplerKmpWriteF32(ptr: Int, data: Float32Array)
internal external fun webrtcResamplerKmpReadS16(ptr: Int, length: Int): Int16Array
internal external fun webrtcResamplerKmpReadF32(ptr: Int, length: Int): Float32Array

// ---- webrtc_resampler_c.h ----

internal external fun webrtc_resampler_version(): String

internal external fun webrtc_resampler_create(): Int
internal external fun webrtc_resampler_create_reset(
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int,
): Int

internal external fun webrtc_resampler_destroy(ptr: Int)
internal external fun webrtc_resampler_reset(
    ptr: Int,
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int,
): Int

internal external fun webrtc_resampler_reset_if_needed(
    ptr: Int,
    inputSampleRate: Int,
    outputSampleRate: Int,
    channels: Int,
): Int

internal external fun webrtc_resampler_push(
    handle: Int,
    inPtr: Int,
    inLength: Int,
    outPtr: Int,
    maxLength: Int,
): Int

internal external fun webrtc_resampler_push_sinc_create(
    sourceFrames: Int,
    destinationFrames: Int,
): Int

internal external fun webrtc_resampler_push_sinc_destroy(ptr: Int)
internal external fun webrtc_resampler_push_sinc_resample_s16(
    handle: Int,
    sourcePtr: Int,
    sourceLength: Int,
    destinationPtr: Int,
    destinationCapacity: Int,
): Int

internal external fun webrtc_resampler_push_sinc_resample_f32(
    handle: Int,
    sourcePtr: Int,
    sourceLength: Int,
    destinationPtr: Int,
    destinationCapacity: Int,
): Int

internal external fun webrtc_resampler_push_sinc_algorithmic_delay_seconds(sourceRateHz: Int): Float

internal external fun webrtc_resampler_push_s16_create(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int,
): Int

internal external fun webrtc_resampler_push_s16_destroy(ptr: Int)
internal external fun webrtc_resampler_push_s16_resample(handle: Int, sourcePtr: Int, destinationPtr: Int)

internal external fun webrtc_resampler_push_f32_create(
    sourceFramesPerChannel: Int,
    destinationFramesPerChannel: Int,
    channels: Int,
): Int

internal external fun webrtc_resampler_push_f32_destroy(ptr: Int)
internal external fun webrtc_resampler_push_f32_resample(handle: Int, sourcePtr: Int, destinationPtr: Int)

internal external fun webrtc_resampler_sinc_kernel_size(): Int

/**
 * Fails with a message a host page can act on when the glue was never loaded or
 * initialized, instead of the bare `ReferenceError` a missing global produces.
 */
private fun wasmModuleInitialized(): Boolean =
    js("typeof webrtcResamplerKmpInitialized !== 'undefined' && webrtcResamplerKmpInitialized === true")

internal fun requireWasmModule() {
    check(wasmModuleInitialized()) {
        "the wasm resampler module is not initialized: await initWebrtcResamplerKmp() from " +
            "@enaium/webrtc-resampler-kmp before using the resampler"
    }
}

/** Runs [block] with a heap buffer of [size] bytes, freed afterwards. */
internal inline fun <T> withHeap(size: Int, block: (ptr: Int) -> T): T {
    val ptr = webrtcResamplerKmpMalloc(size.coerceAtLeast(1))
    try {
        return block(ptr)
    } finally {
        webrtcResamplerKmpFree(ptr)
    }
}
