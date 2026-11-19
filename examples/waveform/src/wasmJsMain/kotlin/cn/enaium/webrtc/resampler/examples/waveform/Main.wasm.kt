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

import kotlin.js.ExperimentalJsExport

/**
 * Browser (wasmJs) entry point.
 *
 * The host page initializes the Emscripten SDL3, Dear ImGui and Web Audio
 * modules before this module is imported, so their globals exist by the time
 * `main` runs.
 *
 * A browser cannot paint while a blocking loop runs, so this drives the example
 * from `requestAnimationFrame`: each frame advances the pipeline by the wall
 * time that passed (Kotlin/Wasm has no threads, [ResamplerPipeline.pump] does
 * what the audio thread does on the other platforms) and draws what it
 * published.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    val frames = requestedFrames()
    val run = WaveformRun(frames)
    val session = ImGuiSdlApp.open(WINDOW_TITLE)
    if (session == null) {
        println("aborted")
        run.close()
        return
    }
    var frame = 0

    fun finish() {
        session.close()
        println("summary: ${run.summary()}")
        println("done")
        run.close()
    }

    fun next() {
        val running = session.frame(frame) {
            run.pump()
            run.draw()
        }
        frame++
        if (!running || frame >= frames) {
            finish()
        } else {
            requestAnimationFrame { next() }
        }
    }

    requestAnimationFrame { next() }
}

/** Frames a headless run stops after; the Node harness sets the global. */
private fun requestedFrames(): Int =
    js("globalThis.webrtcResamplerKmpFrames === undefined ? 2147483647 : globalThis.webrtcResamplerKmpFrames")

private external fun requestAnimationFrame(callback: () -> Unit): Int
