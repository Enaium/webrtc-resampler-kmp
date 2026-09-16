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

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioException
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioOutput
import cn.enaium.audio.AudioSystem
import cn.enaium.audio.SampleFormat
import cn.enaium.audio.audioSystem
import cn.enaium.sdl.SDL
import cn.enaium.webrtc.resampler.PushSincResampler
import cn.enaium.webrtc.resampler.Resampler
import cn.enaium.webrtc.resampler.createPushSincResampler
import cn.enaium.webrtc.resampler.createResampler
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.TimeSource

/** Sample rates the UI offers, in Hz. */
internal val SAMPLE_RATES = intArrayOf(8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 96_000)

/**
 * Highest rate [SAMPLE_RATES] offers: what the scopes are sized for, so
 * switching to it does not invalidate the window that is already drawn.
 */
internal const val MAX_SAMPLE_RATE = 96_000

/** Input rate the example starts at. */
private const val DEFAULT_INPUT_RATE = 48_000

/** Output rate the example starts at: 1:6, so the two traces differ at a glance. */
private const val DEFAULT_OUTPUT_RATE = 8_000

/** Blocks per second every rate is cut into: one block is 10 ms of audio. */
private const val BLOCKS_PER_SECOND = 100

/**
 * Blocks the playback device buffers, in 10 ms blocks.
 *
 * Deeper than the one block the loop writes at a time on purpose: the device
 * has to absorb the jitter of a plain thread, and a queue that runs dry plays
 * silence where the next block should have been.
 */
private const val PLAYBACK_BUFFER_FRAMES = 10

/** Milliseconds between two attempts to open a device that would not open. */
private const val RETRY_DELAY_MILLIS = 250L

/** Milliseconds the loop sleeps between two polls while it is stopped. */
private const val IDLE_MILLIS = 20

/**
 * The int16 range the multi-rate resampler works in, used both ways: the plots
 * and `audio-io-kmp` carry samples in `-1..1`, webrtc's `Resampler` in the
 * int16 range.
 */
private const val INT16_SCALE = 32_768f

/** Per-block weight of the level meters: about a 200 ms time constant. */
private const val METER_SMOOTHING = 0.05

/** Per-block release of the peak meters: about 4 dB/s at 10 ms blocks. */
private const val METER_PEAK_RELEASE = 0.995f

/** Amplitude the source signals are generated at, i.e. -6 dBFS. */
private const val SIGNAL_AMPLITUDE = 0.5f

/** Which webrtc-resampler class does the conversion. */
enum class ResamplerEngine(val label: String) {
    /**
     * `webrtc::Resampler` through `createResampler`: int16 samples, one block
     * in and one block out, and only the fixed rate pairs WebRTC supports - the
     * 8/16/32/44/48/96 kHz combinations without 44 kHz against 48 or 96 kHz and
     * without any 44.1 or 22.05 kHz ratio, which throw
     * [IllegalArgumentException].
     */
    MULTI_RATE("multi-rate (int16, fixed pairs)"),

    /**
     * `webrtc::PushSincResampler` through `createPushSincResampler`: float
     * samples, one block in and one block out, for any ratio - 48 kHz to
     * 44.1 kHz included - at the cost of a filter kernel's worth of delay.
     */
    PUSH_SINC("push sinc (float, arbitrary ratio)"),
}

/** Signal the pipeline feeds the resampler. */
enum class SignalSource(val label: String) {
    /** A 440 Hz sine: one bin, so the level and the period are easy to read off the trace. */
    TONE("440 Hz tone"),

    /** Flat spectrum: every frequency at once, which is where the ratio shows on the traces. */
    NOISE("White noise"),

    /**
     * 50 Hz to 45% of the input rate, in five seconds and over and over. The
     * frequency that the output rate can no longer carry is simply not in the
     * resampled trace, which is what a lower output rate costs.
     */
    SWEEP("Log sweep"),
}

/**
 * Generates the source signal at the input rate.
 *
 * The generator is seeded and its state carries across blocks, so a given
 * signal always produces the same samples - which makes screenshots and
 * measurements comparable - and it stays continuous from one block to the next.
 */
class SignalGenerator(seed: Int = 0x5EED) {

    private val random = Random(seed)

    /** Phase of the tone and of the sweep, in radians. */
    private var phase = 0.0

    /** How far into the sweep the next sample is, in seconds. */
    private var sweepSeconds = 0.0

    /** Fills the first [count] samples of [destination] with [source] at [sampleRate]. */
    fun generate(destination: FloatArray, count: Int, source: SignalSource, sampleRate: Int) {
        when (source) {
            SignalSource.TONE -> {
                val step = 2.0 * PI * TONE_HZ / sampleRate
                for (i in 0 until count) {
                    destination[i] = (sin(phase) * SIGNAL_AMPLITUDE).toFloat()
                    phase = advance(phase, step)
                }
            }

            SignalSource.NOISE -> {
                for (i in 0 until count) {
                    destination[i] = (random.nextFloat() * 2f - 1f) * SIGNAL_AMPLITUDE
                }
            }

            SignalSource.SWEEP -> {
                // One frequency per block: the sweep covers a couple of hertz
                // within 10 ms, and the phase stays continuous across the steps.
                val top = sampleRate * SWEEP_TOP_FRACTION
                val frequency = SWEEP_START_HZ * (top / SWEEP_START_HZ).pow(sweepSeconds / SWEEP_SECONDS)
                val step = 2.0 * PI * frequency / sampleRate
                val sampleSeconds = 1.0 / sampleRate
                for (i in 0 until count) {
                    destination[i] = (sin(phase) * SIGNAL_AMPLITUDE).toFloat()
                    phase = advance(phase, step)
                    sweepSeconds = if (sweepSeconds + sampleSeconds >= SWEEP_SECONDS) {
                        0.0
                    } else {
                        sweepSeconds + sampleSeconds
                    }
                }
            }
        }
    }

    /** Keeps [phase] within one turn, so a long run does not lose precision to it. */
    private fun advance(phase: Double, step: Double): Double {
        val next = phase + step
        return if (next >= TWO_PI) next - TWO_PI else next
    }

    private companion object {
        const val TONE_HZ = 440.0

        /** Lowest frequency of the sweep, in Hz. */
        const val SWEEP_START_HZ = 50.0

        /** One sweep from [SWEEP_START_HZ] to the top of the band takes this long. */
        const val SWEEP_SECONDS = 5.0

        /** Fraction of the input rate the sweep ends below: the top of the band. */
        const val SWEEP_TOP_FRACTION = 0.45

        const val TWO_PI = 2.0 * PI
    }
}

/**
 * A level meter: the mean square and the peak of one signal, in dBFS.
 *
 * Fast enough to follow a tone that starts and slow enough to read: the mean
 * square runs through a one pole of about 200 ms, and the peak holds and
 * releases at about 4 dB/s, which is how a peak meter behaves.
 */
private class LevelMeter {

    private var power = 0.0

    private var peak = 0f

    /** RMS of the signal, in dBFS, or `-inf` while nothing has been measured. */
    var rmsDb = Float.NEGATIVE_INFINITY
        private set

    /** Peak of the signal, in dBFS, or `-inf` while nothing has been measured. */
    var peakDb = Float.NEGATIVE_INFINITY
        private set

    /** Folds one block of [count] samples in. */
    fun track(values: FloatArray, count: Int) {
        if (count <= 0) return
        var sum = 0.0
        var blockPeak = 0f
        for (i in 0 until count) {
            val value = values[i]
            sum += value.toDouble() * value
            val magnitude = abs(value)
            if (magnitude > blockPeak) blockPeak = magnitude
        }
        power += (sum / count - power) * METER_SMOOTHING
        rmsDb = if (power > 0.0) (10.0 * log10(power)).toFloat() else Float.NEGATIVE_INFINITY
        peak = maxOf(blockPeak, peak * METER_PEAK_RELEASE)
        peakDb = if (peak > 0f) (20.0 * log10(peak.toDouble())).toFloat() else Float.NEGATIVE_INFINITY
    }
}

/**
 * The audio half of the example, on a thread of its own ([start]):
 *
 *  - [SignalGenerator] produces the source signal at the input rate, one 10 ms
 *    block at a time.
 *  - Every block goes through the resampler the controls picked - the int16
 *    `webrtc::Resampler`, for the rate pairs it supports, or the float
 *    `webrtc::PushSincResampler`, for any ratio - and the resampled block is
 *    played through the default output device at the output rate, which is what
 *    makes the conversion audible and not just visible.
 *  - The source and the resampled output are published into [inputScope] /
 *    [outputScope] for the plots, which draw the same span of time of both: the
 *    ratio is what the two traces differ by.
 *
 * Unlike a capture loop, the pipeline does not need a device to run - it is the
 * source of the signal itself, and it paces itself off whatever it has: with an
 * output device, that device's clock is the pace, because a write to it only
 * comes back as fast as it takes the blocks; without one, one block of audio is
 * due every 10 ms of wall-clock. A machine with no sound card therefore still
 * fills the traces and the meters, and the device only decides how much of it
 * is heard.
 *
 * The render loop reads the scopes, the statistics and the applied
 * configuration, and writes the controls; everything crossing that boundary is
 * either volatile or published through the atomics inside [WaveformScope].
 */
class ResamplerPipeline(
    /**
     * Samples one waveform window covers; the example sizes it for the longest
     * window its slider allows at [MAX_SAMPLE_RATE] and lets the UI show a
     * shorter part of it.
     */
    windowSamples: Int,
) : AutoCloseable {

    /** Source samples, at the applied input rate. */
    val inputScope = WaveformScope(windowSamples)

    /** Resampled samples, at the applied output rate. */
    val outputScope = WaveformScope(windowSamples)

    /** Backend serving the output device, e.g. `"Core Audio"`. */
    val systemName: String

    /** Input rate the source is generated at. Takes effect on the next block. */
    @Volatile
    var inputRate: Int = DEFAULT_INPUT_RATE

    /** Output rate the resampled signal is played at. Takes effect on the next block. */
    @Volatile
    var outputRate: Int = DEFAULT_OUTPUT_RATE

    /** Resampler the blocks go through. Rebuilds it and drops its filter state. */
    @Volatile
    var engine: ResamplerEngine = ResamplerEngine.MULTI_RATE

    /** Signal generated at the input rate. */
    @Volatile
    var source: SignalSource = SignalSource.TONE

    /** Whether the pipeline generates and plays. Stopped, both traces freeze. */
    @Volatile
    var playing: Boolean = true

    /** Input rate the blocks are actually generated at. */
    @Volatile
    var appliedInputRate: Int = DEFAULT_INPUT_RATE
        private set

    /** Output rate the resampled blocks are actually produced at. */
    @Volatile
    var appliedOutputRate: Int = DEFAULT_OUTPUT_RATE
        private set

    /** Engine actually converting the blocks. */
    @Volatile
    var appliedEngine: ResamplerEngine = ResamplerEngine.MULTI_RATE
        private set

    /** Whether an engine is open at [appliedInputRate] / [appliedOutputRate]. */
    @Volatile
    var resampling: Boolean = false
        private set

    /** Why the requested configuration is not running, or `null` while it is. */
    @Volatile
    var configError: String? = null
        private set

    /** Human readable name of the playback endpoint, once one is open. */
    @Volatile
    var playbackDeviceName: String = "system default"
        private set

    /** Why no playback stream is open, or `null` while one is. */
    @Volatile
    var playbackError: String? = null
        private set

    /** Playback frames the output device could not take in time. */
    @Volatile
    var droppedPlaybackFrames: Long = 0L
        private set

    /** Source samples generated since the pipeline was created. */
    @Volatile
    var inputSamples: Long = 0L
        private set

    /** Resampled samples produced since the pipeline was created. */
    @Volatile
    var outputSamples: Long = 0L
        private set

    /** RMS of the source signal, in dBFS. */
    @Volatile
    var inputRmsDb: Float = Float.NEGATIVE_INFINITY
        private set

    /** Peak of the source signal, in dBFS. */
    @Volatile
    var inputPeakDb: Float = Float.NEGATIVE_INFINITY
        private set

    /** RMS of the resampled signal, in dBFS. */
    @Volatile
    var outputRmsDb: Float = Float.NEGATIVE_INFINITY
        private set

    /** Peak of the resampled signal, in dBFS. */
    @Volatile
    var outputPeakDb: Float = Float.NEGATIVE_INFINITY
        private set

    /**
     * Measured level difference: the RMS of the source minus the RMS of the
     * resampled output, in dB. A resampler that passes the band it keeps
     * unchanged sits at 0 dB; a lower output rate drops what is above its
     * Nyquist limit, which a broadband source pays for and a tone does not.
     */
    @Volatile
    var levelDifferenceDb: Float = Float.NaN
        private set

    /** Audio generated so far, in seconds: the source samples over the input rate. */
    @Volatile
    var audioSeconds: Double = 0.0
        private set

    /**
     * Wall-clock the loop took to produce it, in seconds. Both should track each
     * other: the pair is what says whether the machine keeps up with the blocks
     * it is asked for.
     */
    @Volatile
    var wallSeconds: Double = 0.0
        private set

    /** Format the resampled blocks are handed to the device in. */
    val playbackFormat: AudioFormat get() = AudioFormat(appliedOutputRate, CHANNELS, SampleFormat.PCM_S16)

    /** Samples one generated block holds at [appliedInputRate]. */
    val inputFramesPerBlock: Int get() = framesPerBlock(appliedInputRate)

    /** Samples one resampled block holds at [appliedOutputRate]. */
    val outputFramesPerBlock: Int get() = framesPerBlock(appliedOutputRate)

    private val system: AudioSystem = audioSystem()

    private val generator = SignalGenerator()

    private val clock = TimeSource.Monotonic

    private val startedAt = clock.markNow()

    /** When the current turn started, for [wallSeconds]. */
    private var lastTick = startedAt

    private var wallNanos = 0L

    /** Wall-clock the next block is due at, in nanoseconds since [startedAt]. */
    private var nextBlockNanos = 0L

    /** Earliest wall-clock time the next attempt to open the device may run at. */
    private var nextOpenMillis = 0L

    // Scratch for one block, sized for the highest rate the UI offers so a
    // change of rate never has to allocate on the audio thread.
    private val signal = FloatArray(framesPerBlock(MAX_SAMPLE_RATE))

    private val resampled = FloatArray(framesPerBlock(MAX_SAMPLE_RATE))

    // Exactly one of the two engines is set, and the block buffers are sized by
    // the engine that is set: both factories require their blocks to be the
    // size the block length works out to.
    private var multiRate: Resampler? = null

    private var multiRateInput = ShortArray(0)

    private var multiRateOutput = ShortArray(0)

    private var pushSinc: PushSincResampler? = null

    private var pushSincInput = FloatArray(0)

    private var pushSincOutput = FloatArray(0)

    // The configuration the audio thread has already tried, which is what keeps
    // a rejected rate pair from being rebuilt on every block.
    private var attemptedInputRate = 0

    private var attemptedOutputRate = 0

    private var attemptedEngine: ResamplerEngine? = null

    private val inputMeter = LevelMeter()

    private val outputMeter = LevelMeter()

    @Volatile
    private var output: AudioOutput? = null

    /** Rate the open stream runs at, or 0 while none is open. */
    private var streamRate = 0

    private var playbackBuffer: AudioBuffer? = null

    @Volatile
    private var closed = false

    private var worker: AudioThread? = null

    init {
        systemName = system.name
    }

    /** Starts the audio thread. */
    fun start() {
        if (worker != null || closed) return
        worker = AudioThread("resampler-waveform-audio") { audioLoop() }.also { it.start() }
    }

    /**
     * One line for the console, which is what a headless run has to show for
     * itself: the engine, the rate pair, the samples in and out, and the
     * measured level difference between them - plus the reason when the
     * configuration that was asked for last is not the one that ran. Read it
     * after the run, not while one is going on: the pipeline's own thread owns
     * every field in it.
     */
    fun summary(): String = buildString {
        append("engine ${appliedEngine.label}, $appliedInputRate Hz -> $appliedOutputRate Hz, ")
        append("samples in $inputSamples / out $outputSamples, ")
        append("level difference ${db(levelDifferenceDb)} dB")
        configError?.let { append(", last change rejected: $it") }
    }

    /**
     * Runs until [close], one 10 ms block per turn: the block is produced, then
     * handed to the device as soon as the device has room for it, and the turn
     * waits for the rest of the block either way, so the traces and the
     * statistics keep moving whether or not anything is playing.
     */
    private fun audioLoop() {
        while (!closed) {
            val now = clock.markNow()
            val elapsed = (now - lastTick).inWholeNanoseconds
            lastTick = now
            if (!playing) {
                // The schedule is re-anchored while the loop is stopped: a long
                // pause must not be paid back as a burst of blocks.
                nextBlockNanos = startedAt.elapsedNow().inWholeNanoseconds
                SDL.delay(IDLE_MILLIS)
                continue
            }
            // What the previous turn took is what wallSeconds counts, so the
            // realtime factor in the UI compares like with like.
            wallNanos += elapsed

            applyControls()
            // The block is generated and published before the device is
            // touched: opening a stream can take a moment (the platform's audio
            // server has to be reached), and the traces should be live while it
            // does.
            val frames = processBlock()
            val stream = playback()
            if (stream != null && frames > 0) writePlayback(stream, frames)
            pace(stream)
        }
    }

    /**
     * Waits out the rest of the block.
     *
     * With a device that is the point where the device has room for the next
     * block: the queue is the clock, so the loop runs at the rate the device
     * plays at rather than at the rate this machine's clock does, and a device
     * that is a little slower than the schedule slows the loop instead of
     * costing it blocks. The room is watched here rather than waited for inside
     * the device - a device that parks its writer is not something the example
     * can be sure to be let out of - and the wait is bounded by one block, so a
     * device that has stopped consuming does not stop the traces with it: they
     * keep moving, and what it never takes is counted as dropped frames.
     *
     * Without a device the wall-clock is the only clock there is, one block per
     * block, on a schedule anchored to [startedAt] rather than to the previous
     * turn: a turn that ran late is followed by a shorter wait instead of
     * staying late for the rest of the run.
     */
    private fun pace(stream: AudioOutput?) {
        if (stream != null) {
            // Re-anchored here, so a device that goes away later does not leave
            // the schedule below in the past.
            nextBlockNanos = startedAt.elapsedNow().inWholeNanoseconds
            var waited = 0
            while (!closed && waited < BLOCK_MILLIS) {
                if (stream.available() >= outputFramesPerBlock) return
                SDL.delay(1)
                waited++
            }
            return
        }
        nextBlockNanos += BLOCK_NANOS
        val wait = nextBlockNanos - startedAt.elapsedNow().inWholeNanoseconds
        if (wait > 0L) SDL.delay((wait / NANOS_PER_MILLI).toInt().coerceAtLeast(1))
    }

    /**
     * Applies the control changes the render loop made, on the audio thread:
     * a rate pair, an engine or both rebuild the resampler, which drops its
     * filter state - the traces show a short transient after a change. A pair
     * the engine rejects leaves the previous one running and is reported in
     * [configError] until another configuration is asked for.
     */
    private fun applyControls() {
        if (inputRate == attemptedInputRate && outputRate == attemptedOutputRate && engine == attemptedEngine) return
        attemptedInputRate = inputRate
        attemptedOutputRate = outputRate
        attemptedEngine = engine
        rebuild()
    }

    /** Replaces the resampler with one for the requested engine and rate pair. */
    private fun rebuild() {
        val inputRate = inputRate
        val outputRate = outputRate
        val engine = engine
        try {
            when (engine) {
                ResamplerEngine.MULTI_RATE -> {
                    val resampler = createResampler(inputRate, outputRate, CHANNELS)
                    val input = ShortArray(framesPerBlock(inputRate))
                    val output = ShortArray(framesPerBlock(outputRate))
                    closeEngines()
                    multiRate = resampler
                    multiRateInput = input
                    multiRateOutput = output
                }

                ResamplerEngine.PUSH_SINC -> {
                    val resampler = createPushSincResampler(framesPerBlock(inputRate), framesPerBlock(outputRate))
                    // The block buffers are the resampler's own sizes: it takes
                    // no more and no less than one block per call.
                    val input = FloatArray(resampler.sourceFrames)
                    val output = FloatArray(resampler.destinationFrames)
                    closeEngines()
                    pushSinc = resampler
                    pushSincInput = input
                    pushSincOutput = output
                }
            }
            appliedInputRate = inputRate
            appliedOutputRate = outputRate
            appliedEngine = engine
            resampling = true
            configError = null
            report("resampling $inputRate Hz -> $outputRate Hz with ${engine.label}")
        } catch (e: IllegalArgumentException) {
            // The multi-rate resampler only covers the fixed WebRTC rate pairs,
            // so 44.1 kHz - and 44 kHz against 48 or 96 kHz - is rejected here;
            // the push sinc one takes any ratio.
            configError = e.message
            report("cannot resample $inputRate Hz -> $outputRate Hz with ${engine.label}: ${e.message}")
        }
    }

    /** Closes whatever engine is open; the replacement is already built by then. */
    private fun closeEngines() {
        multiRate?.close()
        multiRate = null
        multiRateInput = ShortArray(0)
        multiRateOutput = ShortArray(0)
        pushSinc?.close()
        pushSinc = null
        pushSincInput = FloatArray(0)
        pushSincOutput = FloatArray(0)
    }

    /**
     * One 10 ms block end to end: generate at the input rate, resample, publish
     * both signals and measure them. Returns the frames resampled into
     * [resampled].
     */
    private fun processBlock(): Int {
        // Nothing to push the block through: the requested configuration was
        // rejected and no earlier one is open either (the first attempt ever,
        // which is what a default pair the engine does not support looks like).
        // Generating into a trace that cannot be resampled would only report
        // samples in without any out.
        if (multiRate == null && pushSinc == null) return 0
        val inputRate = appliedInputRate
        val inputFrames = framesPerBlock(inputRate)
        generator.generate(signal, inputFrames, source, inputRate)
        val produced = resample(inputFrames)

        inputScope.append(signal, inputFrames)
        if (produced > 0) outputScope.append(resampled, produced)

        inputSamples += inputFrames
        outputSamples += produced
        inputMeter.track(signal, inputFrames)
        outputMeter.track(resampled, produced)
        inputRmsDb = inputMeter.rmsDb
        inputPeakDb = inputMeter.peakDb
        outputRmsDb = outputMeter.rmsDb
        outputPeakDb = outputMeter.peakDb
        levelDifferenceDb = inputMeter.rmsDb - outputMeter.rmsDb
        audioSeconds = inputSamples.toDouble() / inputRate
        wallSeconds = wallNanos / NANOS_PER_SECOND
        return produced
    }

    /**
     * Runs one block through the engine that is open and returns the frames
     * written to [resampled]. The multi-rate resampler carries samples in the
     * int16 range - which is the whole reason it is the cheaper one - so the
     * `-1..1` floats of the plots are scaled in and back out; the push sinc
     * resampler takes floats as they are.
     */
    private fun resample(inputFrames: Int): Int {
        val multiRate = multiRate
        if (multiRate != null) {
            val input = multiRateInput
            for (i in 0 until inputFrames) {
                input[i] = (signal[i] * INT16_SCALE).roundToInt().coerceIn(-32_768, 32_767).toShort()
            }
            val produced = multiRate.resample(input, multiRateOutput)
            for (i in 0 until produced) resampled[i] = multiRateOutput[i] / INT16_SCALE
            return produced
        }

        val pushSinc = pushSinc ?: return 0
        val input = pushSincInput
        signal.copyInto(input, 0, 0, inputFrames)
        val produced = pushSinc.resample(input, pushSincOutput)
        pushSincOutput.copyInto(resampled, 0, 0, produced)
        return produced
    }

    /**
     * The open playback stream, opening one when there is none. `null` while
     * there is no device, or none that takes the output rate: the example keeps
     * generating, measuring and drawing, it just cannot be heard.
     */
    private fun playback(): AudioOutput? {
        // Nothing is being resampled, so nothing is worth playing: opening a
        // device only to hand it nothing is how an example ends up holding a
        // speaker open for no reason. Neither is one opened on the way out.
        if (!resampling || closed) return null
        val open = output
        if (open != null) {
            if (streamRate == appliedOutputRate) return open
            // The output rate moved: blocks resampled for the new rate cannot be
            // played through a stream opened for the old one.
            open.close()
            output = null
            playbackBuffer = null
            streamRate = 0
        }
        if (millis() < nextOpenMillis) return null
        return openPlayback()
    }

    private fun openPlayback(): AudioOutput? = try {
        val rate = appliedOutputRate
        val format = AudioFormat(rate, CHANNELS, SampleFormat.PCM_S16)
        val stream = system.openOutput(format, bufferFrames = framesPerBlock(rate) * PLAYBACK_BUFFER_FRAMES)
        if (stream.format != format) {
            // The blocks were resampled for this rate, so a device that opens as
            // something else would play them at the wrong speed. Leave it closed
            // and report it rather than playing the wrong thing.
            if (playbackError == null) {
                playbackError = "device opened as ${stream.format}, not $format"
                report("no playback: $playbackError")
            }
            stream.close()
            nextOpenMillis = millis() + RETRY_DELAY_MILLIS
            return null
        }
        stream.start()
        // Prime the queue with silence. The loop hands the device one block per
        // block of wall-clock and never waits for it, so the queue is the only
        // thing absorbing the jitter of a plain thread; a device that runs dry
        // plays silence where the next block should have been.
        val primeFrames = framesPerBlock(rate) * PLAYBACK_PRIME_FRAMES
        val prime = AudioBuffer(format, primeFrames)
        prime.putFloats(FloatArray(primeFrames * CHANNELS))
        stream.writeNonBlocking(prime)
        playbackBuffer = AudioBuffer(format, framesPerBlock(rate))
        playbackDeviceName = stream.device?.name ?: system.defaultOutputDevice()?.name ?: "system default"
        playbackError = null
        streamRate = rate
        output = stream
        report("playing to $playbackDeviceName [$systemName] $format")
        stream
    } catch (e: AudioException) {
        if (playbackError == null) {
            val available = system.outputDevices().joinToString { it.name }
            playbackError = "${e.message} - outputs: [$available]"
            report("playback unavailable: $playbackError; running without it")
        }
        nextOpenMillis = millis() + RETRY_DELAY_MILLIS
        null
    }

    private fun writePlayback(stream: AudioOutput, frames: Int) {
        val buffer = playbackBuffer ?: return
        buffer.putFloats(resampled, frames)
        // What the device has room for goes out; [pace] has already waited for a
        // block's worth of it, so this is the whole block whenever the device
        // keeps up, and the remainder is counted as dropped when it does not.
        // Waiting inside the device instead would tie this thread to it, and a
        // device that never takes the block would never give the thread back.
        val written = stream.writeNonBlocking(buffer)
        if (written < frames) droppedPlaybackFrames += (frames - maxOf(written, 0)).toLong()
    }

    /** Stops the audio thread and releases the devices, the resampler and the backend. */
    override fun close() {
        if (closed) return
        closed = true
        // Closing the stream unblocks a thread that is waiting on it, and
        // join() then guarantees it is out before anything it uses is
        // destroyed. Nothing waits inside the device (see [pace]), so this is a
        // formality rather than the only way out of it.
        output?.close()
        worker?.join()
        worker = null
        output = null
        playbackBuffer = null
        closeEngines()
        system.close()
    }

    private fun millis(): Long = startedAt.elapsedNow().inWholeMilliseconds

    private companion object {
        const val CHANNELS = 1

        const val NANOS_PER_SECOND = 1_000_000_000.0

        const val NANOS_PER_MILLI = 1_000_000L

        /** Every block of every rate covers this many milliseconds of audio. */
        const val BLOCK_MILLIS = 10

        const val BLOCK_NANOS = BLOCK_MILLIS * NANOS_PER_MILLI

        /** Blocks of silence the queue is primed with when the device is opened. */
        const val PLAYBACK_PRIME_FRAMES = PLAYBACK_BUFFER_FRAMES - 2

        fun framesPerBlock(rate: Int): Int = rate / BLOCKS_PER_SECOND
    }
}
