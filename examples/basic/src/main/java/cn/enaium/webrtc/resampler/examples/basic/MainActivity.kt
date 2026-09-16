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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Hosts the loopback and the permission request.
 *
 * The permission is requested the first time the user starts the loopback;
 * [ResamplerLoopbackController.start] is only called once it is granted, so a
 * denied permission is reported to the user instead of failing inside the
 * audio device.
 */
class MainActivity : ComponentActivity() {

    private val controller = ResamplerLoopbackController()

    /** `true` after the user denied the microphone permission. */
    private var permissionDenied by mutableStateOf(false)

    private val recordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (!permissionDenied) controller.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ResamplerLoopbackScreen(
                    controller = controller,
                    permissionDenied = permissionDenied,
                    onSelectEngine = { controller.selectEngine(it) },
                    onSelectOutputRate = { controller.selectOutputSampleRate(it) },
                    onToggle = { toggleLoopback() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.stop()
    }

    private fun toggleLoopback() {
        if (controller.isRunning) {
            controller.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        permissionDenied = false
        controller.start()
    }
}

/**
 * Renders the state of [controller] and forwards the user's selections back to
 * it; all audio work happens in the controller.
 */
@Composable
fun ResamplerLoopbackScreen(
    controller: ResamplerLoopbackController,
    permissionDenied: Boolean,
    onSelectEngine: (ResamplerEngine) -> Unit,
    onSelectOutputRate: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    // The controller holds its state in mutableStateOf, which Compose observes,
    // so a plain read is enough to recompose on change.
    val engine = controller.engine
    val outputSampleRate = controller.outputSampleRate
    val isRunning = controller.isRunning
    val error = controller.error
    val samplesIn = controller.samplesIn
    val samplesOut = controller.samplesOut
    val inputRmsDbfs = controller.inputRmsDbfs
    val outputRmsDbfs = controller.outputRmsDbfs
    val wallMillis = controller.wallMillis
    val audioMillis = controller.audioMillis

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        // ---- Engine ----
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.engine),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val engines = ResamplerEngine.entries
            engines.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == engine,
                    onClick = { onSelectEngine(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, engines.size),
                    label = { Text(stringResource(engineLabel(value))) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(engineNote(engine)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // ---- Output rate ----
        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.output_rate),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ResamplerLoopbackController.OUTPUT_SAMPLE_RATES.forEach { rate ->
                FilterChip(
                    selected = rate == outputSampleRate,
                    onClick = { onSelectOutputRate(rate) },
                    label = { Text(stringResource(rateLabel(rate))) },
                )
            }
        }

        // ---- Live statistics ----
        // Everything that reports rather than controls folds away: the numbers
        // are one tap behind this row, so the controls and the state of the
        // loopback stay in view on a phone.
        Spacer(Modifier.height(16.dp))

        HorizontalDivider()

        var statisticsExpanded by remember { mutableStateOf(false) }

        TextButton(
            onClick = { statisticsExpanded = !statisticsExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (statisticsExpanded) R.string.hide_statistics else R.string.show_statistics,
                ),
            )
        }

        if (statisticsExpanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.conversion,
                        stringResource(rateLabel(ResamplerLoopbackController.INPUT_SAMPLE_RATE)),
                        stringResource(rateLabel(outputSampleRate)),
                        ResamplerLoopbackController.INPUT_FRAMES_PER_BLOCK,
                        controller.outputFramesPerBlock,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.samples_in, samplesIn),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.samples_out, samplesOut),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.input_level, levelText(inputRmsDbfs)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.output_level, levelText(outputRmsDbfs)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.wall_time, wallMillis),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.audio_time, audioMillis),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.drift, wallMillis - audioMillis),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ---- Status ----
        // Failures stay visible: a permission denial or an audio device error
        // that requires unfolding a panel is a failure the user does not see.
        if (permissionDenied) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.error, message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        // ---- Start / Stop ----
        Spacer(Modifier.height(24.dp))

        if (isRunning) {
            OutlinedButton(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.stop))
            }
        } else {
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start))
            }
        }
    }
}

/** Label resource of one engine option. */
private fun engineLabel(engine: ResamplerEngine): Int = when (engine) {
    ResamplerEngine.FIXED_RATES -> R.string.engine_fixed
    ResamplerEngine.ARBITRARY_RATIO -> R.string.engine_arbitrary
}

/** Note resource describing what one engine can and cannot convert. */
private fun engineNote(engine: ResamplerEngine): Int = when (engine) {
    ResamplerEngine.FIXED_RATES -> R.string.engine_note_fixed
    ResamplerEngine.ARBITRARY_RATIO -> R.string.engine_note_arbitrary
}

/**
 * Label resource of one of the output rates the rate selector offers, i.e. of
 * one [ResamplerLoopbackController.OUTPUT_SAMPLE_RATES] entry.
 */
private fun rateLabel(sampleRate: Int): Int = when (sampleRate) {
    8_000 -> R.string.rate_8000
    16_000 -> R.string.rate_16000
    32_000 -> R.string.rate_32000
    44_100 -> R.string.rate_44100
    48_000 -> R.string.rate_48000
    else -> throw IllegalArgumentException("no label for $sampleRate Hz")
}

/** [value] with one decimal, or the silence marker for digital silence. */
@Composable
private fun levelText(value: Float): String =
    if (value.isFinite()) "%.1f".format(value) else stringResource(R.string.level_silence)
