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

import kotlin.system.exitProcess

/** Parses `--frames N` (exit after N frames, for headless runs). */
private fun parseFrames(args: Array<String>): Int {
    var frames = Int.MAX_VALUE
    var i = 0
    while (i < args.size) {
        if (args[i] == "--frames" && i + 1 < args.size) {
            frames = args[i + 1].toIntOrNull() ?: Int.MAX_VALUE
            i++
        }
        i++
    }
    return frames
}

/**
 * On macOS SDL has to own the first thread, so a JVM launched without
 * `-XstartOnFirstThread` (every IDE run configuration by default) cannot open
 * a window.
 */
internal actual val videoInitHint: String =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        "\nThe JVM must start on the first thread for SDL: add -XstartOnFirstThread to the VM options." +
            "\n:examples:waveform:jvmRun sets it already; IDE run configurations have to add it themselves."
    } else {
        ""
    }

/** Set on the relaunched JVM so a failure there cannot start another one. */
private const val RELAUNCH_MARKER = "RESAMPLER_KMP_WAVEFORM_RELAUNCHED"

/** JVM entry point: `./gradlew :examples:waveform:jvmRun`. */
fun main(args: Array<String>) {
    if (runWaveformExample(parseFrames(args))) return
    // No window could be opened. On macOS that is what happens when the JVM was
    // not started on the process' first thread, which SDL needs to create one -
    // `./gradlew :examples:waveform:jvmRun` passes -XstartOnFirstThread, an IDE
    // run configuration and a plain `java -cp` do not. Rather than failing with
    // an instruction, rerun the example in a JVM that has it.
    if (isMacOs && System.getenv(RELAUNCH_MARKER) == null) {
        val status = relaunchOnFirstThread(args)
        if (status != null) exitProcess(status)
    }
    exitProcess(1)
}

private val isMacOs: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * Starts this entry point again in a child JVM launched with
 * `-XstartOnFirstThread`, forwarding stdio and the exit status, or returns
 * `null` when the child cannot be started (the caller then reports the original
 * failure).
 */
private fun relaunchOnFirstThread(args: Array<String>): Int? = try {
    val java = ProcessHandle.current().info().command().orElse(null)
        ?: "${System.getProperty("java.home")}/bin/java"
    val command = listOf(java, "-XstartOnFirstThread", "-cp", System.getProperty("java.class.path"), MAIN_CLASS) + args
    println("rerunning on the first thread: ${command.joinToString(" ")}")
    val child = ProcessBuilder(command)
        .inheritIO()
        .apply { environment()[RELAUNCH_MARKER] = "1" }
        .start()
    // An IDE's stop button only kills this JVM; take the child with it.
    Runtime.getRuntime().addShutdownHook(Thread { child.destroy() })
    child.waitFor()
} catch (e: Exception) {
    println("could not rerun the example: $e")
    null
}

private const val MAIN_CLASS = "cn.enaium.webrtc.resampler.examples.waveform.Main_jvmKt"
