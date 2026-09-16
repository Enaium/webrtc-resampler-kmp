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

import cn.enaium.webrtc.resampler.resamplerVersion

/**
 * Platform specific advice appended when SDL cannot open a video device, or
 * empty when the platform has nothing to add.
 */
internal expect val videoInitHint: String

/**
 * Live resampling visualization, shared by the JVM and native entry points: a
 * source signal is generated at the input rate, pushed through one of the two
 * resamplers of the binding in 10 ms blocks, played at the output rate and
 * drawn with ImPlot next to the signal it came from.
 *
 * The scopes are sized for the longest window the UI can show at the highest
 * rate it offers, so widening the window or raising the rate needs no more than
 * a change of the controls.
 *
 * [frames] bounds the run, which is what the automated (headless) runs use.
 * Returns `false` when no window could be opened.
 */
fun runWaveformExample(frames: Int = Int.MAX_VALUE): Boolean {
    println("webrtc-resampler-kmp waveform example (frames=$frames, webrtc-resampler ${resamplerVersion()})")
    val displayed = ResamplerPipeline(windowSamples(MAX_WINDOW_MILLIS, MAX_SAMPLE_RATE)).use { pipeline ->
        val window = WaveformWindow(pipeline)
        // Generation, resampling and playback run on the pipeline's own thread;
        // this loop only draws what it publishes.
        pipeline.start()
        val rendered = ImGuiSdlApp.run("webrtc-resampler-kmp waveform", frames) {
            window.draw()
        }
        // What a headless run has to show for itself: the engine and rate pair
        // it ran with, how many samples went in and came out of it, and how far
        // the level moved between the two.
        println("summary: ${pipeline.summary()}")
        rendered
    }
    println(if (displayed) "done" else "aborted")
    return displayed
}
