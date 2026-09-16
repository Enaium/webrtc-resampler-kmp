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

package cn.enaium.webrtc.resampler.examples.waveform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar

/**
 * Native audio thread, built on POSIX threads: Kotlin/Native has no common
 * thread API, and this is the smallest thing that gives the pipeline a thread of
 * its own on every native target.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
internal actual class AudioThread actual constructor(
    @Suppress("unused") name: String,
    private val body: () -> Unit,
) {

    /**
     * `pthread_t` is a pointer on Apple platforms and an integer on Linux, so
     * the handle lives in native memory and only its address is carried around.
     */
    private val handle = nativeHeap.alloc<pthread_tVar>()

    private var reference: StableRef<AudioThread>? = null

    actual fun start() {
        if (reference != null) return
        // The body is a Kotlin closure on the Kotlin heap; the stable reference
        // is what lets the C entry point reach it, and is released in join().
        val ref = StableRef.create(this)
        reference = ref
        val created = pthread_create(
            handle.ptr,
            null,
            staticCFunction { argument ->
                argument?.asStableRef<AudioThread>()?.get()?.body?.invoke()
                null
            },
            ref.asCPointer(),
        )
        check(created == 0) { "pthread_create failed: $created" }
    }

    actual fun join() {
        val ref = reference ?: return
        reference = null
        pthread_join(handle.value, null)
        nativeHeap.free(handle.ptr.rawValue)
        ref.dispose()
    }
}
