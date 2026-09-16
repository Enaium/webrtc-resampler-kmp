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
 * The thread the audio pipeline runs on.
 *
 * Signal generation, resampling and playback live here and never on the render
 * loop: a device that stalls (or simply takes its time to open) must not freeze
 * the window, and the render loop must not decide how fast audio moves. The
 * loop is paced by the playback device instead - it blocks in `AudioOutput.write`
 * - or, where there is no device, by a sleep for one block, so the example
 * still produces audio when nothing can play it.
 */
internal expect class AudioThread(name: String, body: () -> Unit) {

    /** Starts the thread; calling it twice is a no-op. */
    fun start()

    /** Waits for the thread to finish; calling it without a start is a no-op. */
    fun join()
}
