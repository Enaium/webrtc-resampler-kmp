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

package cn.enaium.webrtc.resampler.examples.basic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.enaium.webrtc.resampler.PushSincResampler
import cn.enaium.webrtc.resampler.Resampler
import cn.enaium.webrtc.resampler.createPushSincResampler
import cn.enaium.webrtc.resampler.createResampler
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/** Which of the two resampler families the loopback runs. */
enum class ResamplerEngine {
    /**
     * `webrtc::Resampler` through [createResampler]: the integer multi-rate
     * resampler, which only covers the fixed WebRTC rate pairs.
     */
    FIXED_RATES,

    /**
     * `webrtc::PushSincResampler` through [createPushSincResampler]: the
     * windowed sinc resampler, which covers arbitrary ratios such as
     * 48 kHz -> 44.1 kHz.
     */
    ARBITRARY_RATIO,
}

/**
 * Owns the real-time resampler loopback and exposes the Compose-observable
 * state the UI collects:
 *
 *  - [AudioRecord] captures the microphone at [INPUT_SAMPLE_RATE] (48 kHz,
 *    mono, 16 bit PCM).
 *  - Every 10 ms block of [INPUT_FRAMES_PER_BLOCK] samples is pushed through the
 *    resampler selected by [engine] and [outputSampleRate].
 *  - [AudioTrack] plays the converted block back at the output rate, so an
 *    output rate other than 48 kHz is audible as a pitch shift of the
 *    microphone signal.
 *
 * [engine] and [outputSampleRate] may change while running: the loopback is
 * restarted so that the resampler is re-created for the new pair. A pair the
 * selected family cannot express (44.1 kHz with [ResamplerEngine.FIXED_RATES])
 * is reported through [error] instead of crashing.
 *
 * The statistics ([samplesIn], [samplesOut], [inputRmsDbfs], [outputRmsDbfs],
 * [wallMillis], [audioMillis]) are refreshed every 250 ms, which makes a
 * stalled or drifting audio path visible: [wallMillis] - [audioMillis] grows
 * when the device does not keep up with real time.
 *
 * Creating the audio devices needs the `RECORD_AUDIO` permission; without it
 * the device construction fails and [error] carries the reason.
 */
class ResamplerLoopbackController {

    companion object {
        private const val TAG = "ResamplerExample"

        private const val MILLIS_PER_SECOND = 1000
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val BYTES_PER_SAMPLE = 2

        /** Blocks of slack the audio devices buffer. */
        private const val BUFFER_BLOCKS = 4

        /** Empty reads tolerated before the microphone counts as gone. */
        private const val MAX_EMPTY_READS = 100

        /** Rate the microphone is captured at; the fixed rate pairs are all derived from it. */
        const val INPUT_SAMPLE_RATE = 48000

        /** One block is 10 ms long, the frame size WebRTC resamplers are used with. */
        const val BLOCK_MILLIS = 10

        /** Samples per channel of one block at [INPUT_SAMPLE_RATE]. */
        const val INPUT_FRAMES_PER_BLOCK = INPUT_SAMPLE_RATE * BLOCK_MILLIS / MILLIS_PER_SECOND

        private const val CHANNELS = 1
        private const val SAMPLES_PER_MILLI = INPUT_SAMPLE_RATE / MILLIS_PER_SECOND

        /** Blocks between two statistics updates, i.e. 250 ms of audio. */
        private const val STATS_INTERVAL_FRAMES = 25L

        /** Upper bound for the audio thread to leave its loop in [stop]. */
        private const val STOP_TIMEOUT_MILLIS = 500L

        /**
         * Output rates the UI offers, in Hz.
         *
         * [ResamplerEngine.ARBITRARY_RATIO] handles all of them,
         * [ResamplerEngine.FIXED_RATES] rejects 44.1 kHz because
         * `webrtc::Resampler` has no 44.1 kHz ratio.
         */
        val OUTPUT_SAMPLE_RATES = listOf(8_000, 16_000, 32_000, 44_100, 48_000)
    }

    /** Resampler family the loopback runs. */
    var engine by mutableStateOf(ResamplerEngine.FIXED_RATES)
        private set

    /** Playback rate of the output device; the capture rate is always [INPUT_SAMPLE_RATE]. */
    var outputSampleRate by mutableIntStateOf(16_000)
        private set

    /** `true` between a successful [start] and the end of the audio loop. */
    var isRunning by mutableStateOf(false)
        private set

    /** Reason the loopback failed, cleared by the next [start]. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Input samples captured since [start]. */
    var samplesIn by mutableLongStateOf(0L)
        private set

    /** Output samples written since [start]. */
    var samplesOut by mutableLongStateOf(0L)
        private set

    /** Input level of the last statistics interval in dBFS, `-Infinity` without signal. */
    var inputRmsDbfs by mutableFloatStateOf(Float.NEGATIVE_INFINITY)
        private set

    /** Output level of the last statistics interval in dBFS, `-Infinity` without signal. */
    var outputRmsDbfs by mutableFloatStateOf(Float.NEGATIVE_INFINITY)
        private set

    /** Wall clock time since [start]. */
    var wallMillis by mutableLongStateOf(0L)
        private set

    /** Audio time processed since [start]; [wallMillis] minus this is the drift. */
    var audioMillis by mutableLongStateOf(0L)
        private set

    /** Samples per channel the selected output rate produces from one 10 ms block. */
    val outputFramesPerBlock: Int
        get() = outputSampleRate * BLOCK_MILLIS / MILLIS_PER_SECOND

    private var blockResampler: BlockResampler? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var worker: Thread? = null

    /**
     * Selects the resampler family; a running loopback is restarted with the
     * new one.
     */
    fun selectEngine(value: ResamplerEngine) {
        if (engine == value) return
        engine = value
        if (isRunning) restart()
    }

    /**
     * Selects the output rate; a running loopback is restarted with it.
     *
     * @throws IllegalArgumentException when [value] is not one of [OUTPUT_SAMPLE_RATES].
     */
    fun selectOutputSampleRate(value: Int) {
        require(value in OUTPUT_SAMPLE_RATES) { "$value Hz is not one of $OUTPUT_SAMPLE_RATES" }
        if (outputSampleRate == value) return
        outputSampleRate = value
        if (isRunning) restart()
    }

    /**
     * Creates the resampler and the audio devices and starts the audio loop on
     * a background thread; does nothing when the loopback already runs.
     */
    fun start() {
        if (isRunning) return
        worker?.let { previous ->
            previous.join(STOP_TIMEOUT_MILLIS)
            if (previous.isAlive) {
                error = "the previous audio loop is still running"
                return
            }
            worker = null
        }

        error = null
        try {
            // Storing every object right away means a later failure can release
            // what was already opened.
            val resampler = createBlockResampler().also { blockResampler = it }
            val record = createAudioRecord().also { audioRecord = it }
            val track = createAudioTrack().also { audioTrack = it }

            samplesIn = 0L
            samplesOut = 0L
            inputRmsDbfs = Float.NEGATIVE_INFINITY
            outputRmsDbfs = Float.NEGATIVE_INFINITY
            wallMillis = 0L
            audioMillis = 0L

            isRunning = true
            worker = thread(name = "resampler-loopback") {
                processingLoop(record, track, resampler)
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "failed to start the loopback", t)
            isRunning = false
            release()
        }
    }

    /**
     * Stops the audio loop and waits for it to release the audio devices and
     * the resampler.
     *
     * The release happens on the worker thread (see [processingLoop]), so this
     * waits for the loop to leave; [STOP_TIMEOUT_MILLIS] bounds that wait when
     * the loop is stuck in a blocking audio call, and the worker releases
     * everything once that call returns. [start] refuses to run while such a
     * worker is still alive, so its resources are never closed underneath it.
     */
    fun stop() {
        isRunning = false
        val current = worker ?: return
        current.join(STOP_TIMEOUT_MILLIS)
        if (current.isAlive) {
            Log.w(TAG, "the audio loop did not stop within $STOP_TIMEOUT_MILLIS ms")
        }
    }

    private fun restart() {
        stop()
        start()
    }

    // =========================================================================
    // Resampler
    // =========================================================================

    private fun createBlockResampler(): BlockResampler = when (engine) {
        // Fixed WebRTC rate pairs only; 44.1 kHz is rejected here with an
        // IllegalArgumentException from the binding.
        ResamplerEngine.FIXED_RATES -> FixedRateBlockResampler(
            createResampler(INPUT_SAMPLE_RATE, outputSampleRate, CHANNELS),
            outputFramesPerBlock,
        )
        // One 10 ms block in, outputSampleRate / 100 samples out, at any ratio.
        ResamplerEngine.ARBITRARY_RATIO -> PushSincBlockResampler(
            createPushSincResampler(INPUT_FRAMES_PER_BLOCK, outputFramesPerBlock),
        )
    }

    // =========================================================================
    // Audio I/O
    // =========================================================================

    private fun createAudioRecord(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize failed with $minBufferBytes for $INPUT_SAMPLE_RATE Hz"
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, INPUT_FRAMES_PER_BLOCK * BYTES_PER_SAMPLE * BUFFER_BLOCKS),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(
                "AudioRecord could not be initialized at $INPUT_SAMPLE_RATE Hz " +
                    "(is the microphone in use?)",
            )
        }
        return record
    }

    private fun createAudioTrack(): AudioTrack {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) {
            "AudioTrack.getMinBufferSize failed with $minBufferBytes for $outputSampleRate Hz"
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(outputSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(
                maxOf(minBufferBytes, outputFramesPerBlock * BYTES_PER_SAMPLE * BUFFER_BLOCKS),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException(
                "AudioTrack could not be initialized at $outputSampleRate Hz " +
                    "(does the output device support the rate?)",
            )
        }
        return track
    }

    /** Closes what [start] opened: audio devices first, then the resampler. */
    private fun release() {
        audioRecord?.let { record ->
            runCatching { record.stop() }
                .onFailure { Log.w(TAG, "AudioRecord.stop failed", it) }
            record.release()
        }
        audioTrack?.let { track ->
            runCatching { track.stop() }
                .onFailure { Log.w(TAG, "AudioTrack.stop failed", it) }
            track.release()
        }
        blockResampler?.close()

        audioRecord = null
        audioTrack = null
        blockResampler = null
    }

    // =========================================================================
    // Processing loop
    // =========================================================================

    /** Runs on the worker thread until [stop] or a failure; owns [release]. */
    private fun processingLoop(record: AudioRecord, track: AudioTrack, resampler: BlockResampler) {
        val input = ShortArray(INPUT_FRAMES_PER_BLOCK)
        val output = ShortArray(resampler.destinationFrames)
        val startedNanos = System.nanoTime()

        var captured = 0L
        var played = 0L
        var frames = 0L
        var intervalInputSamples = 0L
        var intervalOutputSamples = 0L
        var intervalInputPower = 0.0
        var intervalOutputPower = 0.0

        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not start recording"
            }
            track.play()

            while (isRunning) {
                if (!readBlock(record, input)) break
                val written = resampler.resample(input, output)
                writeBlock(track, output, written)

                captured += input.size
                played += written
                intervalInputPower += sumOfSquares(input, input.size)
                intervalInputSamples += input.size
                intervalOutputPower += sumOfSquares(output, written)
                intervalOutputSamples += written
                frames++

                if (frames % STATS_INTERVAL_FRAMES == 0L) {
                    samplesIn = captured
                    samplesOut = played
                    inputRmsDbfs = dbfs(intervalInputPower, intervalInputSamples)
                    outputRmsDbfs = dbfs(intervalOutputPower, intervalOutputSamples)
                    wallMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
                    audioMillis = captured / SAMPLES_PER_MILLI
                    Log.i(
                        TAG,
                        "samples in=$captured out=$played, " +
                            "input=${inputRmsDbfs} dBFS, output=${outputRmsDbfs} dBFS, " +
                            "wall=$wallMillis ms, audio=$audioMillis ms",
                    )
                    intervalInputSamples = 0L
                    intervalOutputSamples = 0L
                    intervalInputPower = 0.0
                    intervalOutputPower = 0.0
                }
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "the loopback audio loop failed", t)
        } finally {
            isRunning = false
            release()
        }
    }

    /**
     * Fills [block] completely.
     *
     * @return `false` when the loop was stopped before the block was full.
     * @throws IllegalStateException when the microphone stops delivering data.
     */
    private fun readBlock(record: AudioRecord, block: ShortArray): Boolean {
        var offset = 0
        var emptyReads = 0
        while (offset < block.size) {
            if (!isRunning) return false
            val read = record.read(block, offset, block.size - offset)
            if (read < 0) {
                throw IllegalStateException("AudioRecord.read failed with code $read")
            }
            if (read == 0) {
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord stopped recording")
                }
                // A blocking read returns as soon as one sample is available,
                // so repeated empty reads mean the microphone went away.
                if (++emptyReads > MAX_EMPTY_READS) {
                    throw IllegalStateException("AudioRecord delivered no samples")
                }
                continue
            }
            emptyReads = 0
            offset += read
        }
        return true
    }

    /**
     * Writes [count] samples of [block] to [track], which blocks until the
     * output device has consumed them; a slow device therefore shows up as
     * drift between the wall and audio time.
     *
     * @throws IllegalStateException when the output device stops accepting samples.
     */
    private fun writeBlock(track: AudioTrack, block: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val written = track.write(block, offset, count - offset)
            if (written <= 0) {
                throw IllegalStateException(
                    "AudioTrack.write wrote $written of ${count - offset} samples",
                )
            }
            offset += written
        }
    }
}

/** One audio block in, one block out, whichever resampler family is behind it. */
private interface BlockResampler : AutoCloseable {
    /** Samples per channel this resampler writes per [resample] call. */
    val destinationFrames: Int

    /** Resamples one input block into [destination]. */
    fun resample(source: ShortArray, destination: ShortArray): Int
}

/** `webrtc::Resampler`, which derives the output size from its rate pair. */
private class FixedRateBlockResampler(
    private val delegate: Resampler,
    override val destinationFrames: Int,
) : BlockResampler {
    override fun resample(source: ShortArray, destination: ShortArray): Int =
        delegate.resample(source, destination)

    override fun close() = delegate.close()
}

/** `webrtc::PushSincResampler`, whose block sizes are fixed at creation time. */
private class PushSincBlockResampler(
    private val delegate: PushSincResampler,
) : BlockResampler {
    override val destinationFrames: Int = delegate.destinationFrames

    override fun resample(source: ShortArray, destination: ShortArray): Int =
        delegate.resample(source, destination)

    override fun close() = delegate.close()
}

/** Sum of squares of the first [count] int16 [samples], accumulated as doubles. */
private fun sumOfSquares(samples: ShortArray, count: Int): Double {
    var sum = 0.0
    for (i in 0 until count) {
        val sample = samples[i].toDouble()
        sum += sample * sample
    }
    return sum
}

/**
 * Level in dBFS of [count] int16 samples whose total power is [power], i.e.
 * relative to int16 full scale; digital silence maps to
 * `Float.NEGATIVE_INFINITY`.
 */
private fun dbfs(power: Double, count: Long): Float {
    if (count <= 0L) return Float.NEGATIVE_INFINITY
    val rms = sqrt(power / count)
    if (rms <= 0.0) return Float.NEGATIVE_INFINITY
    return (20.0 * log10(rms / 32768.0)).toFloat()
}
