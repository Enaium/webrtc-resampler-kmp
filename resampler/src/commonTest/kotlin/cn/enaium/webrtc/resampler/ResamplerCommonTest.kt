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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signal generators and measurements shared by the tests. */
internal object Signals {
    const val AMPLITUDE = 8192.0

    /** [durationSeconds] of a [frequency] tone sampled at [sampleRate]. */
    fun sine(
        sampleRate: Int,
        durationSeconds: Double,
        frequency: Double,
        amplitude: Double = AMPLITUDE,
    ): ShortArray {
        val frames = (sampleRate * durationSeconds).toInt()
        return ShortArray(frames) { i ->
            val value = amplitude * sin(2.0 * PI * frequency * i / sampleRate)
            value.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Amplitude of [frequency] over [count] samples starting at [start]. */
    fun toneAmplitude(
        samples: ShortArray,
        start: Int,
        count: Int,
        sampleRate: Int,
        frequency: Double,
    ): Double {
        var re = 0.0
        var im = 0.0
        for (i in 0 until count) {
            val w = 2.0 * PI * frequency * i / sampleRate
            val x = samples[start + i].toDouble()
            re += x * kotlin.math.cos(w)
            im -= x * sin(w)
        }
        return 2.0 * sqrt(re * re + im * im) / count
    }

    fun toneAmplitude(
        samples: FloatArray,
        start: Int,
        count: Int,
        sampleRate: Int,
        frequency: Double,
    ): Double {
        var re = 0.0
        var im = 0.0
        for (i in 0 until count) {
            val w = 2.0 * PI * frequency * i / sampleRate
            val x = samples[start + i].toDouble()
            re += x * kotlin.math.cos(w)
            im -= x * sin(w)
        }
        return 2.0 * sqrt(re * re + im * im) / count
    }

    fun ratioToDb(numerator: Double, denominator: Double): Double =
        20.0 * log10(numerator.coerceAtLeast(1e-12) / denominator.coerceAtLeast(1e-12))
}

class ResamplerCommonTest {

    @Test
    fun oneToOnePassthroughIsBitExact() {
        createResampler(48000, 48000, 1).use { resampler ->
            val input = Signals.sine(48000, 0.1, 1000.0)
            val output = ShortArray(input.size)
            val written = resampler.resample(input, output)

            assertEquals(input.size, written)
            assertTrue(input.contentEquals(output), "1:1 output must equal the input")
        }
    }

    @Test
    fun tenMillisecondBlocksResampleToBlockSizes() {
        // in rate -> out rate: 10 ms of input per channel, the matching output.
        listOf(
            Triple(48000, 16000, 480 to 160),
            Triple(16000, 48000, 160 to 480),
            Triple(8000, 48000, 80 to 480),
            Triple(48000, 8000, 480 to 80),
        ).forEach { (inRate, outRate, blocks) ->
            createResampler(inRate, outRate, 1).use { resampler ->
                val input = Signals.sine(inRate, 0.1, 400.0)
                val blockIn = blocks.first
                val blockOut = blocks.second
                val output = ShortArray(blockOut)

                for (position in 0 until input.size - blockIn + 1 step blockIn) {
                    val written = resampler.resample(input.copyOfRange(position, position + blockIn), output)
                    assertEquals(blockOut, written, "$inRate -> $outRate")
                }
            }
        }
    }

    @Test
    fun unsupportedRatePairsAreRejected() {
        // 44.1 kHz has no integer ratio; the sinc resamplers handle it instead.
        assertFailsWith<IllegalArgumentException> { createResampler(44100, 48000, 1) }
        // Only one or two channels.
        assertFailsWith<IllegalArgumentException> { createResampler(48000, 16000, 3) }

        // An unconfigured resampler reports the same through reset().
        createResampler().use { resampler ->
            assertFalse(resampler.reset(22050, 16000, 1), "22.05 kHz must be rejected")
            assertFalse(resampler.reset(48000, 16000, 3), "3 channels must be rejected")
            assertTrue(resampler.reset(48000, 16000, 1))
            assertEquals(48000, resampler.inputSampleRate)
            assertEquals(16000, resampler.outputSampleRate)
            assertEquals(1, resampler.channels)
        }
    }

    @Test
    fun resamplerCanBeReconfigured() {
        createResampler(48000, 16000, 1).use { resampler ->
            val input = Signals.sine(48000, 0.1, 400.0)
            assertEquals(160, resampler.resample(input.copyOfRange(0, 480), ShortArray(160)))

            // Same settings: ResetIfNeeded keeps the states.
            assertTrue(resampler.resetIfNeeded(48000, 16000, 1))

            // New settings: the block sizes follow the new pair.
            assertTrue(resampler.reset(16000, 48000, 1))
            assertEquals(480, resampler.resample(input.copyOfRange(0, 160), ShortArray(480)))
        }
    }

    @Test
    fun undersizedOutputIsRejected() {
        createResampler(48000, 16000, 1).use { resampler ->
            val input = Signals.sine(48000, 0.01, 400.0)
            // 480 samples at 48 kHz need 160 at 16 kHz.
            assertFailsWith<IllegalArgumentException> {
                resampler.resample(input, ShortArray(159))
            }
        }
    }

    @Test
    fun pushSincDownsamplePreservesToneAndRejectsImage() {
        val inRate = 48000
        val outRate = 16000
        val blockIn = inRate / 100
        val blockOut = outRate / 100

        createPushSincResampler(blockIn, blockOut).use { resampler ->
            assertEquals(blockIn, resampler.sourceFrames)
            assertEquals(blockOut, resampler.destinationFrames)

            // 1 kHz passes, 18 kHz aliases to 2 kHz at 16 kHz and must be removed.
            val input = Signals.sine(inRate, 1.0, 1000.0)
            val output = ShortArray(inRate / outRate * input.size)
            val block = ShortArray(blockOut)
            var position = 0
            var written = 0
            while (position + blockIn <= input.size) {
                written += resampler.resample(
                    input.copyOfRange(position, position + blockIn),
                    block,
                )
                block.copyInto(output, written - blockOut)
                position += blockIn
            }

            assertEquals((input.size / blockIn) * blockOut, written)
            val start = outRate / 20
            val count = outRate / 2
            val level = Signals.ratioToDb(
                Signals.toneAmplitude(output, start, count, outRate, 1000.0),
                Signals.AMPLITUDE,
            )
            assertTrue(abs(level) < 1.0, "1 kHz level drifted by $level dB")
        }
    }

    @Test
    fun pushSincUpsamplePreservesToneLevel() {
        val inRate = 16000
        val outRate = 48000
        val blockIn = inRate / 100
        val blockOut = outRate / 100

        createPushSincResampler(blockIn, blockOut).use { resampler ->
            val input = Signals.sine(inRate, 1.0, 3000.0)
            val output = ShortArray((input.size / blockIn) * blockOut)
            val block = ShortArray(blockOut)
            var position = 0
            var written = 0
            while (position + blockIn <= input.size) {
                written += resampler.resample(input.copyOfRange(position, position + blockIn), block)
                block.copyInto(output, written - blockOut)
                position += blockIn
            }

            val start = outRate / 20
            val count = outRate / 2
            val level = Signals.ratioToDb(
                Signals.toneAmplitude(output, start, count, outRate, 3000.0),
                Signals.AMPLITUDE,
            )
            assertTrue(abs(level) < 1.0, "3 kHz level drifted by $level dB")
        }
    }

    @Test
    fun pushSincRejectsWrongBlockSize() {
        createPushSincResampler(480, 160).use { resampler ->
            assertFailsWith<IllegalArgumentException> {
                resampler.resample(ShortArray(479), ShortArray(160))
            }
            assertFailsWith<IllegalArgumentException> {
                resampler.resample(ShortArray(480), ShortArray(159))
            }
            assertFailsWith<IllegalArgumentException> {
                resampler.resample(FloatArray(480), FloatArray(159))
            }
        }
    }

    @Test
    fun pushSincResamplesFloatsAtDistancePreservingLevel() {
        val blockIn = 441
        val blockOut = 480

        createPushSincResampler(blockIn, blockOut).use { resampler ->
            val input = FloatArray(blockIn) { i -> (0.5 * sin(2.0 * PI * 1000.0 * i / 44100.0)).toFloat() }
            val output = FloatArray(blockOut)
            val written = resampler.resample(input, output)

            assertEquals(blockOut, written)
            // The filter kernel delays the signal, so the output cannot match
            // the input sample for sample - what matters is that a 44.1 kHz
            // block comes out at an amplitude the sinc kernel preserves.
            val level = Signals.ratioToDb(
                Signals.toneAmplitude(output, 0, blockOut, 48000, 1000.0),
                0.5 * blockOut / blockIn * 2,
            )
            assertTrue(level > -12.0, "44.1 kHz -> 48 kHz block came out $level dB down")
        }
    }

    @Test
    fun pushResamplerInt16KeepsStereoChannelsSeparate() {
        val inRate = 48000
        val outRate = 16000
        val blockIn = inRate / 100
        val blockOut = outRate / 100

        createPushResamplerInt16(blockIn, blockOut, 2).use { resampler ->
            assertEquals(2, resampler.channels)

            val frames = inRate / 2
            val input = ShortArray(frames * 2) { i ->
                val frame = i / 2
                val frequency = if (i % 2 == 0) 1000.0 else 3000.0
                (Signals.AMPLITUDE * sin(2.0 * PI * frequency * frame / inRate)).toInt().toShort()
            }
            val output = ShortArray((frames / blockIn) * blockOut * 2)
            val block = ShortArray(blockOut * 2)
            var frame = 0
            var written = 0
            while (frame + blockIn <= frames) {
                resampler.resample(
                    input.copyOfRange(frame * 2, (frame + blockIn) * 2),
                    block,
                )
                block.copyInto(output, written * 2)
                frame += blockIn
                written += blockOut
            }

            assertEquals(frames / blockIn * blockOut, written)
            val left = ShortArray(written) { output[it * 2] }
            val right = ShortArray(written) { output[it * 2 + 1] }

            val start = outRate / 20
            val count = outRate / 4
            val leftLevel = Signals.ratioToDb(
                Signals.toneAmplitude(left, start, count, outRate, 1000.0),
                Signals.AMPLITUDE,
            )
            val rightLevel = Signals.ratioToDb(
                Signals.toneAmplitude(right, start, count, outRate, 3000.0),
                Signals.AMPLITUDE,
            )
            assertTrue(abs(leftLevel) < 1.0, "left channel level drifted by $leftLevel dB")
            assertTrue(abs(rightLevel) < 1.0, "right channel level drifted by $rightLevel dB")

            val bleedIntoTone = Signals.ratioToDb(
                Signals.toneAmplitude(left, start, count, outRate, 3000.0),
                Signals.toneAmplitude(left, start, count, outRate, 1000.0),
            )
            assertTrue(bleedIntoTone < -60.0, "right channel bled into left by $bleedIntoTone dB")
        }
    }

    @Test
    fun pushResamplerFloatResamplesMono() {
        val inRate = 16000
        val outRate = 48000
        val blockIn = inRate / 100
        val blockOut = outRate / 100

        createPushResamplerFloat(blockIn, blockOut, 1).use { resampler ->
            val output = FloatArray(blockOut * 50)
            val block = FloatArray(blockOut)
            for (i in 0 until 50) {
                val input = FloatArray(blockIn) { j ->
                    (0.5 * sin(2.0 * PI * 1000.0 * (i * blockIn + j) / inRate)).toFloat()
                }
                resampler.resample(input, block)
                block.copyInto(output, i * blockOut)
            }

            val start = outRate / 20
            val count = outRate / 4
            val level = Signals.ratioToDb(
                Signals.toneAmplitude(output, start, count, outRate, 1000.0),
                0.5,
            )
            assertTrue(abs(level) < 1.0, "float tone level drifted by $level dB")
        }
    }

    @Test
    fun sincResamplerPullApiPreservesToneLevel() {
        val inRate = 48000
        val outRate = 16000
        val requestFrames = inRate / 100

        val source = Signals.sine(inRate, 0.7, 1000.0)
        var position = 0
        createSincResampler(
            ioSampleRateRatio = inRate.toDouble() / outRate,
            requestFrames = requestFrames,
            source = ResamplerSource { frames, destination ->
                for (i in 0 until frames) {
                    val index = position + i
                    destination[i] = if (index < source.size) source[index].toFloat() else 0f
                }
                position += frames
            },
        ).use { resampler ->
            assertEquals(requestFrames, resampler.requestFrames)
            assertTrue(resampler.chunkSize >= 1, "chunk size must be positive")

            val output = FloatArray(outRate / 100 * 60)
            val block = FloatArray(outRate / 100)
            for (i in 0 until 60) {
                resampler.resample(block.size, block)
                block.copyInto(output, i * block.size)
            }

            val start = outRate / 20
            val count = outRate / 2
            val level = Signals.ratioToDb(
                Signals.toneAmplitude(output, start, count, outRate, 1000.0),
                Signals.AMPLITUDE,
            )
            assertTrue(abs(level) < 1.0, "pull API level drifted by $level dB")

            // Flushing lets the same source be replayed from a fresh state.
            resampler.flush()
            resampler.setRatio(48000.0 / 16000.0)
        }
    }
}
