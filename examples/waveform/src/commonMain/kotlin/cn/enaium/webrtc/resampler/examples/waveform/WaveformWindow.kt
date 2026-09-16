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

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotAxisFlags
import cn.enaium.imgui.extensions.implot.ImPlotCond
import cn.enaium.imgui.extensions.implot.ImPlotSpec
import cn.enaium.webrtc.resampler.resamplerVersion
import cn.enaium.webrtc.resampler.sincResamplerKernelSize
import kotlin.math.round

/** Shortest window the slider allows, in milliseconds. */
internal const val MIN_WINDOW_MILLIS = 100

/** Longest window the slider allows, in milliseconds. */
internal const val MAX_WINDOW_MILLIS = 10_000

/** Window the example starts with, in milliseconds. */
internal const val DEFAULT_WINDOW_MILLIS = 1_000

/** Samples a window of [millis] holds at [rate]. */
internal fun windowSamples(millis: Int, rate: Int): Int = millis * rate / 1000

/** Milliseconds one sample covers at [rate], for the time axis. */
private fun millisPerSample(rate: Int): Double = 1000.0 / rate

/**
 * Formats [value] with [decimals] digits after the point. `String.format` is
 * JVM only, and the example is shared with the native targets.
 */
internal fun fixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(value * factor).toLong()
    val magnitude = if (scaled < 0) -scaled else scaled
    val whole = magnitude / factor
    val fraction = (magnitude % factor).toString().padStart(decimals, '0')
    return "${if (scaled < 0) "-" else ""}$whole.$fraction"
}

/**
 * Formats a level in dB with one digit, or `n/a` when there is nothing to
 * report (a signal that is exactly zero is `-inf` dBFS, and the difference
 * between two of them is not a number).
 */
internal fun db(value: Float): String = if (value.isFinite()) fixed(value.toDouble(), 1) else "n/a"

/**
 * The example UI: the source signal at the input rate and the resampled signal
 * at the output rate drawn as two ImPlot lines over the same sliding window,
 * with the controls that pick the signal, the rates, the engine and the window,
 * and the statistics of the blocks that were processed.
 *
 * Both plots share the window in milliseconds, which is the point of showing
 * them one above the other: the same span of time covers 48000 samples at the
 * input rate and 8000 at the output one, so the output trace is visibly
 * coarser, and whatever the output rate cannot carry is simply missing from it.
 * Each plot follows its own amplitude - a resampler cuts the band it cannot
 * carry, so the two signals do not have the same level for every source - and
 * the status header keeps the absolute values, in dBFS, comparable.
 *
 * The window length is a slider: the scopes always hold [MAX_WINDOW_MILLIS] at
 * the highest rate the UI offers and the plots show the newest part of them.
 */
class WaveformWindow(
    private val pipeline: ResamplerPipeline,
    initialWindowMillis: Int = DEFAULT_WINDOW_MILLIS,
) {

    private val sliderValue = IntArray(1) { initialWindowMillis.coerceIn(MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS) }

    private val playing = BooleanArray(1) { pipeline.playing }

    private val engineIndex = IntArray(1) { pipeline.engine.ordinal }

    private val sourceIndex = IntArray(1) { pipeline.source.ordinal }

    private val inputRateIndex = IntArray(1) { SAMPLE_RATES.indexOf(pipeline.inputRate).coerceAtLeast(0) }

    private val outputRateIndex = IntArray(1) { SAMPLE_RATES.indexOf(pipeline.outputRate).coerceAtLeast(0) }

    private var windowMillis = sliderValue[0]

    /**
     * Min/max envelope of the source and of the resampled signal: one pair per
     * plot column, written by the scopes and drawn as they are. Sized by the
     * plot width rather than the window, so a ten second window costs no more
     * memory than a short one.
     */
    private var inputEnvelope = FloatArray(0)

    private var outputEnvelope = FloatArray(0)

    /** Amplitude axis of the input plot, in `-1..1`. */
    private var inputScale = MIN_SCALE

    /** Amplitude axis of the output plot, in `-1..1`. */
    private var outputScale = MIN_SCALE

    /** One frame of the UI. Call between [ImGui.newFrame] and [ImGui.render]. */
    fun draw() {
        // One sweep per scope, and only over what is drawn: the scopes hand back
        // the min/max pairs the plots need, so nothing is copied out and walked
        // a second time on the render thread.
        // The plots are min/max pairs, and ImGui indexes its vertices with 16
        // bits: an anti-aliased polyline costs a handful of vertices per point,
        // so a full-width envelope on a 2712 px display runs past the 65535
        // vertex limit and the last drawn line is wrapped into nonsense. A cap
        // of a few hundred columns also keeps the frame cost bounded.
        val columns = minOf(maxOf(ImGui.getIO().displaySize.x.toInt(), MIN_COLUMNS), MAX_COLUMNS)
        if (inputEnvelope.size != columns * 2) {
            inputEnvelope = FloatArray(columns * 2)
            outputEnvelope = FloatArray(columns * 2)
        }
        // The whole span of time is covered by both plots, at each signal's own
        // sample count: that ratio is what the two traces differ by.
        val inputRate = pipeline.appliedInputRate
        val outputRate = pipeline.appliedOutputRate
        val inputStep = step(windowSamples(windowMillis, inputRate), columns)
        val outputStep = step(windowSamples(windowMillis, outputRate), columns)
        val inputPeak = pipeline.inputScope.scan(inputStep, columns, inputEnvelope)
        val outputPeak = pipeline.outputScope.scan(outputStep, columns, outputEnvelope)
        // Fast attack, slow release: the axis follows a louder passage at once
        // and shrinks back over roughly a second.
        inputScale = maxOf(inputPeak * HEADROOM, inputScale * RELEASE, MIN_SCALE)
        outputScale = maxOf(outputPeak * HEADROOM, outputScale * RELEASE, MIN_SCALE)

        val displaySize = ImGui.getIO().displaySize
        // The window owns the whole viewport: pinned to the top-left corner,
        // resized to the display and stripped of decorations, so it cannot be
        // dragged, resized or collapsed and always fills the SDL window.
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(displaySize, ImGuiCond.ALWAYS)
        if (ImGui.begin("webrtc-resampler-kmp waveform", null, WINDOW_FLAGS)) {
            // The control row: no labels in the window, every control carries
            // its explanation as a tooltip and the widgets themselves say what
            // they are (a checkbox, a combo, a slider with its unit). They flow
            // like words in a paragraph, so however narrow the window is, the
            // row wraps instead of clipping.
            newRow()

            placeCheck("play", playing) { pipeline.playing = playing[0] }
            ImGui.setItemTooltip("Stops the generator: both traces freeze where they are.")

            placeCombo(COMBO_WIDTH) {
                if (ImGui.combo("##engine", engineIndex, ENGINE_LABELS)) {
                    pipeline.engine = ResamplerEngine.entries[engineIndex[0]]
                }
            }
            ImGui.setItemTooltip(
                "Which webrtc-resampler class does the conversion. The multi-rate one is " +
                    "webrtc::Resampler: int16 blocks, and only the fixed rate pairs WebRTC supports - " +
                    "44.1 kHz is not one of them. The push sinc one is webrtc::PushSincResampler: " +
                    "float blocks, any ratio (48 kHz to 44.1 kHz included), at the cost of a filter " +
                    "kernel's worth of delay. Changing it rebuilds the resampler, which drops its " +
                    "filter state.",
            )

            placeCombo(COMBO_WIDTH) {
                if (ImGui.combo("##source", sourceIndex, SOURCE_LABELS)) {
                    pipeline.source = SignalSource.entries[sourceIndex[0]]
                }
            }
            ImGui.setItemTooltip(
                "What is fed to the resampler. The sweep makes the cost of a low output rate " +
                    "audible and visible: whatever is above half the output rate is not in the " +
                    "resampled trace at all.",
            )

            placeCombo(COMBO_WIDTH) {
                if (ImGui.combo("##inputrate", inputRateIndex, RATE_LABELS)) {
                    pipeline.inputRate = SAMPLE_RATES[inputRateIndex[0]]
                }
            }
            ImGui.setItemTooltip("Rate the source signal is generated at.")

            placeCombo(COMBO_WIDTH) {
                if (ImGui.combo("##outputrate", outputRateIndex, RATE_LABELS)) {
                    pipeline.outputRate = SAMPLE_RATES[outputRateIndex[0]]
                }
            }
            ImGui.setItemTooltip(
                "Rate the signal is resampled to and played at. A pair the selected engine does not " +
                    "support leaves the previous one running and is reported below.",
            )

            placeSlider(SLIDER_WIDTH) {
                if (ImGui.sliderInt("##window", sliderValue, MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS, "%d ms")) {
                    windowMillis = sliderValue[0]
                }
            }
            ImGui.setItemTooltip("Length of the plotted window: both traces cover the same span of time.")

            // Only the controls stay on screen: everything that reports on the
            // run - what the blocks did, and the devices, configuration and
            // library behind them - folds away, so the plots get the height and
            // the numbers are one click away.
            ImGui.separator()
            if (ImGui.collapsingHeader("status")) {
                statistics()
                ImGui.separator()
                status()
            }
            ImGui.separator()

            // The window is fullscreen, so the two plots split whatever height
            // is left instead of leaving the lower part empty.
            val available = ImGui.getContentRegionAvail()
            val plotHeight = maxOf((available.y - PLOT_GAP) / 2f, MIN_PLOT_HEIGHT)
            plot(
                "input (${inputRate} Hz)",
                inputEnvelope,
                inputStep,
                inputRate,
                INPUT_COLOR,
                plotHeight,
                inputScale,
            )
            plot(
                "output (${outputRate} Hz)",
                outputEnvelope,
                outputStep,
                outputRate,
                OUTPUT_COLOR,
                plotHeight,
                outputScale,
            )
        }
        ImGui.end()
    }

    /**
     * What the blocks did: how many samples went in and came out, the level of
     * both signals in dBFS, the difference between them, and how much
     * wall-clock that took against the audio time it covers. Drawn inside the
     * status header, like everything else that reports rather than controls.
     */
    private fun statistics() {
        val input = pipeline.inputSamples
        val output = pipeline.outputSamples
        val ratio = if (input > 0L) output.toDouble() / input else Double.NaN
        ImGui.text(
            "samples: in $input at ${pipeline.appliedInputRate} Hz, out $output at " +
                "${pipeline.appliedOutputRate} Hz " +
                "(ratio ${if (ratio.isFinite()) fixed(ratio, 3) else "n/a"})",
        )
        ImGui.text(
            "level: input ${db(pipeline.inputRmsDb)} dBFS rms / ${db(pipeline.inputPeakDb)} dBFS peak, " +
                "output ${db(pipeline.outputRmsDb)} dBFS rms / ${db(pipeline.outputPeakDb)} dBFS peak, " +
                "difference ${db(pipeline.levelDifferenceDb)} dB",
        )
        val wall = pipeline.wallSeconds
        val realtime = if (wall > 0.0) pipeline.audioSeconds / wall else Double.NaN
        ImGui.text(
            "time: audio ${fixed(pipeline.audioSeconds, 2)} s, wall ${fixed(wall, 2)} s " +
                "(${if (realtime.isFinite()) fixed(realtime, 2) else "n/a"}x)",
        )
    }

    /**
     * Devices, configuration and library: everything the statistics do not say,
     * drawn below them in the same header.
     */
    private fun status() {
        val playbackError = pipeline.playbackError
        if (playbackError == null) {
            val dropped = pipeline.droppedPlaybackFrames
            ImGui.text(
                "playback: ${pipeline.playbackDeviceName} via ${pipeline.systemName} " +
                    "${pipeline.playbackFormat}" +
                    // A device that stops consuming is worth showing: the window
                    // keeps running, but the audio is no longer what is drawn.
                    if (dropped > 0L) " (dropped $dropped frames)" else "",
            )
        } else {
            ImGui.text("no playback, running without it: $playbackError")
        }

        val configError = pipeline.configError
        ImGui.text(
            "engine: ${pipeline.appliedEngine.label}, ${pipeline.appliedInputRate} Hz -> " +
                "${pipeline.appliedOutputRate} Hz, block ${pipeline.inputFramesPerBlock} in / " +
                "${pipeline.outputFramesPerBlock} out samples (10 ms)",
        )
        if (configError != null) {
            ImGui.text(
                "cannot switch to ${pipeline.engine.label} at ${pipeline.inputRate} Hz -> " +
                    "${pipeline.outputRate} Hz: $configError",
            )
        }
        ImGui.text(
            "webrtc-resampler ${resamplerVersion()}, sinc kernel ${sincResamplerKernelSize()} taps",
        )
    }

    // =========================================================================
    // Layout
    //
    // A row of controls does not fit in a narrow window, and a widget that is
    // cut off at the edge cannot be operated, so the controls go into rows that
    // wrap: every helper below measures what it is about to draw and starts a
    // new row when the rest of the current one is too small. The width of a
    // widget is the frame ImGui draws plus, for a checkbox, the label inside it.
    // =========================================================================

    /** Room the controls already placed on the current row take up. */
    private var rowWidth = 0f

    /** Starts a new control row. */
    private fun newRow() {
        rowWidth = 0f
    }

    /** Reserves [width] on the current row, wrapping onto a new one when it does not fit. */
    private fun reserve(width: Float) {
        val available = ImGui.getContentRegionAvail().x
        val needed = if (rowWidth > 0f) ITEM_GAP + width else width
        if (rowWidth > 0f && rowWidth + needed > available) {
            // The cursor is already on the next line, an item leaves it there.
            rowWidth = width
        } else {
            if (rowWidth > 0f) {
                ImGui.sameLine()
                rowWidth += ITEM_GAP
            }
            rowWidth += width
        }
    }

    /** A checkbox: the check square plus the label it carries. */
    private fun placeCheck(label: String, value: BooleanArray, onChange: () -> Unit) {
        reserve(ImGui.getFrameHeight() + LABEL_GAP + ImGui.calcTextSize(label).x)
        if (ImGui.checkbox(label, value)) onChange()
    }

    /** A slider [itemWidth] wide; what it does is in its tooltip. */
    private fun placeSlider(itemWidth: Float, block: () -> Unit) {
        reserve(itemWidth)
        ImGui.setNextItemWidth(itemWidth)
        block()
    }

    /** A dropdown [itemWidth] wide. */
    private fun placeCombo(itemWidth: Float, block: () -> Unit) {
        reserve(itemWidth)
        ImGui.setNextItemWidth(itemWidth)
        block()
    }

    /**
     * Draws one envelope. Every pair of points covers [step] samples at [rate],
     * which is what the x scale is derived from, and the cost of the line is
     * bounded by the plot width instead of the window length.
     */
    private fun plot(
        title: String,
        envelope: FloatArray,
        step: Int,
        rate: Int,
        color: ImVec4,
        height: Float,
        scale: Float,
    ) {
        if (!ImPlot.beginPlot(title, ImVec2(-1f, height))) return
        ImPlot.setupAxes("milliseconds", "amplitude", ImPlotAxisFlags.NONE, ImPlotAxisFlags.NONE)
        // The window scrolls, so the x axis is pinned to the full sweep.
        ImPlot.setupAxesLimits(
            0.0,
            windowMillis.toDouble(),
            -scale.toDouble(),
            scale.toDouble(),
            ImPlotCond.ALWAYS,
        )
        ImPlot.plotLine(
            "##$title",
            envelope,
            xScale = step / 2.0 * millisPerSample(rate),
            spec = ImPlotSpec(lineColor = color, lineWeight = 1f),
        )
        ImPlot.endPlot()
    }

    private companion object {
        /** Fullscreen host window: no title bar, nothing to drag or resize. */
        const val WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
            ImGuiWindowFlags.NO_NAV_FOCUS

        /** Space kept between the two plots. */
        val PLOT_GAP: Float get() = ImGui.getFontSize() * 0.5f

        val ENGINE_LABELS = ResamplerEngine.entries.map { it.label }.toTypedArray()

        val SOURCE_LABELS = SignalSource.entries.map { it.label }.toTypedArray()

        val RATE_LABELS = SAMPLE_RATES.map { "$it Hz" }.toTypedArray()

        /** Floor for very small windows. */
        val MIN_PLOT_HEIGHT: Float get() = ImGui.getFontSize() * 4f

        /** Guarantees the envelope has at least one column to fill. */
        const val MIN_COLUMNS = 1

        /**
         * Upper bound on the envelope width. 1024 columns is 2048 points per
         * plot - a few thousand vertices even with anti-aliasing, so two plots
         * stay well inside ImGui's 16 bit index range - and still resolves a
         * couple of pixels of a wide display.
         */
        const val MAX_COLUMNS = 1024

        /**
         * Widths the flow reserves for a control, and the gap kept between two.
         * Relative to the font, so a slider that fits "384 ms" is the same
         * fraction of a line whatever the display scales the font to.
         */
        val SLIDER_WIDTH: Float get() = ImGui.getFontSize() * 8f

        val COMBO_WIDTH: Float get() = ImGui.getFontSize() * 13f

        /**
         * Space between two controls of a row and between a checkbox's square
         * and its label: the style's own spacing.
         */
        val ITEM_GAP: Float get() = ImGui.getFontSize() * 8f / 13f

        val LABEL_GAP: Float get() = ImGui.getFontSize() * 4f / 13f

        /** Keeps the peaks below the top of the axis. */
        const val HEADROOM = 1.15f

        /** Per-frame release factor of the amplitude scale. */
        const val RELEASE = 0.97f

        const val MIN_SCALE = 1e-4f

        val INPUT_COLOR = ImVec4(0.45f, 0.65f, 0.95f, 1f)

        val OUTPUT_COLOR = ImVec4(0.35f, 0.85f, 0.5f, 1f)

        /** Samples one envelope column covers, at least one. */
        fun step(samples: Int, columns: Int): Int = maxOf((samples + columns - 1) / columns, 1)
    }
}
