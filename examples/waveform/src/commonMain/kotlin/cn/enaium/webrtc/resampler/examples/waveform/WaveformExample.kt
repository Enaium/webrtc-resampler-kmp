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

/** Title of the example window, shared by the blocking and cooperative entries. */
internal const val WINDOW_TITLE = "webrtc-resampler-kmp waveform"

/**
 * A waveform example run: the pipeline, the window drawn over it and the
 * console lines that report on it. Both drivers use it - the blocking one in
 * [runWaveformExample] and the cooperative one the wasmJs entry drives from
 * `requestAnimationFrame`.
 */
internal class WaveformRun(frames: Int) : AutoCloseable {

    private val pipeline = ResamplerPipeline(windowSamples(MAX_WINDOW_MILLIS, MAX_SAMPLE_RATE))

    private val window = WaveformWindow(pipeline)

    init {
        println("webrtc-resampler-kmp waveform example (frames=$frames, webrtc-resampler ${resamplerVersion()})")
    }

    /** Starts the audio thread: generation, resampling and playback run there. */
    fun start() = pipeline.start()

    /** Advances the pipeline one display frame's worth, where there is no thread. */
    fun pump() = pipeline.pump()

    /** Draws one frame with the state the pipeline published. */
    fun draw() = window.draw()

    /**
     * What a headless run has to show for itself: the engine and rate pair it
     * ran with, how many samples went in and came out of it, and how far the
     * level moved between the two.
     */
    fun summary(): String = pipeline.summary()

    override fun close() = pipeline.close()
}


