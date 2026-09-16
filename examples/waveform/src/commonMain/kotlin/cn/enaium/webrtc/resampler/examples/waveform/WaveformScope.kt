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

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs

/**
 * A sliding window of mono samples: appended by the audio thread, read by the
 * render loop.
 *
 * The two never block each other, which is what matters here: the writer is
 * feeding a device, so it publishes samples through an atomic index and the
 * reader only looks at the part that is already published. A reader can catch
 * the writer overwriting the oldest samples of a full window, which makes one
 * frame mix two instants - for a live scope that is a cosmetic difference and
 * far cheaper than making the audio thread wait on a lock.
 */
@OptIn(ExperimentalAtomicApi::class)
class WaveformScope(val capacity: Int) {

    private val samples = FloatArray(capacity)

    /** Samples appended since the scope was created; only the writer advances it. */
    private val appended = AtomicLong(0L)

    /** Samples currently in the window. */
    val available: Int get() = minOf(appended.load(), capacity.toLong()).toInt()

    /** Appends [count] samples, overwriting the oldest ones once full. */
    fun append(values: FloatArray, count: Int = values.size) {
        var index = writeIndex()
        for (i in 0 until count) {
            samples[index] = values[i]
            if (++index == capacity) index = 0
        }
        // Published last: everything a reader can see of the window is already
        // written.
        appended.store(appended.load() + count)
    }

    /**
     * Fills [envelope] with one min/max pair per column over the newest
     * `samplesPerColumn * columns` samples: each pair covers the same slice of
     * time, which is what makes the drawn line follow the signal instead of
     * jumping between extremes, and the pairs are written in sample order so the
     * time axis stays monotonic. Returns the peak magnitude in the window.
     *
     * The plots run this on every frame, and the window can hold half a million
     * samples, so it walks the window once instead of copying it out first.
     */
    fun scan(samplesPerColumn: Int, columns: Int, envelope: FloatArray): Float {
        val step = maxOf(samplesPerColumn, 1)
        if (columns <= 0 || envelope.size < columns * 2 || available <= 0) {
            envelope.fill(0f)
            return 0f
        }

        // The sweep always covers the full window and ends on the newest sample,
        // so "now" is at the right edge of the plot. Before a window's worth of
        // audio exists the start reaches back past it, and the ring still holds
        // the zeros it was created with - which is what the left of the plot
        // should show. Starting at the oldest *available* sample instead would
        // shift everything right and leave the right edge flat.
        var position = oldestIndex(step * columns)
        var peak = 0f
        var written = 0
        repeat(columns) {
            var lowestIndex = position
            var highestIndex = position
            var index = position
            for (i in 0 until step) {
                val value = samples[index]
                if (value < samples[lowestIndex]) lowestIndex = index
                if (value > samples[highestIndex]) highestIndex = index
                if (++index == capacity) index = 0
            }
            position = index

            val first = minOf(lowestIndex, highestIndex)
            val second = maxOf(lowestIndex, highestIndex)
            val lowest = samples[first]
            val highest = samples[second]
            envelope[written++] = lowest
            envelope[written++] = highest
            if (abs(lowest) > peak) peak = abs(lowest)
            if (abs(highest) > peak) peak = abs(highest)
        }
        return peak
    }

    /** Index the next sample is written to. */
    private fun writeIndex(): Int = (appended.load() % capacity).toInt()

    /** Index of the sample [count] before the newest one. */
    private fun oldestIndex(count: Int): Int {
        val index = writeIndex() - count
        return if (index < 0) index + capacity else index
    }
}
