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
 * This is the blocking driver: the audio thread produces the blocks and the
 * loop here draws them. The wasmJs entry cannot block a browser, so it drives
 * [WaveformRun.pump] and [ImGuiSdlApp.Session.frame] from `requestAnimationFrame`
 * instead.
 *
 * [frames] bounds the run, which is what the automated (headless) runs use.
 * Returns `false` when no window could be opened.
 *
 * It lives next to [ImGuiSdlApp] rather than in commonMain because imgui-kmp
 * publishes its SDL backends for the JVM, native and wasmJs targets only.
 */
fun runWaveformExample(frames: Int = Int.MAX_VALUE): Boolean {
    WaveformRun(frames).use { run ->
        // Generation, resampling and playback run on the pipeline's own thread;
        // this loop only draws what it publishes.
        run.start()
        val rendered = ImGuiSdlApp.run(WINDOW_TITLE, frames) { run.draw() }
        println("summary: ${run.summary()}")
        println(if (rendered) "done" else "aborted")
        return rendered
    }
}
