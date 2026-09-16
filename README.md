# webrtc-resampler-kmp

![](https://img.cdn1.vip/i/6aaaa946e13f4_1789569350.webp)

Kotlin Multiplatform bindings for the [WebRTC resampler](https://github.com/Enaium/webrtc-resampler) — the resampler extracted from WebRTC's `common_audio/resampler`, with its signal processing subset. It covers both the integer multi-rate resampler (`webrtc::Resampler`) and the high quality windowed sinc resamplers (`webrtc::SincResampler`, `webrtc::PushSincResampler`, `webrtc::PushResampler<T>`) that handle arbitrary ratios such as 44.1 kHz ⟷ 48 kHz.

## Supported Platforms

| Platform       | Targets                                                     | Mechanism                                  |
| -------------- | ----------------------------------------------------------- | ------------------------------------------ |
| **Android**    | arm64-v8a, armeabi-v7a, x86, x86_64                          | JNI (shared library via CMake)             |
| **Android (Kotlin/Native)** | arm64-v8a, armeabi-v7a, x86, x86_64             | Kotlin/Native cinterop (static library)    |
| **JVM**        | Linux x86_64/aarch64, macOS arm64/x86_64, Windows x86_64     | JNI (per-OS/arch JAR resource, auto-extracted by `NativeLoader`) |
| **iOS**        | arm64, x64, simulatorArm64                                   | Kotlin/Native cinterop (static library)    |
| **macOS**      | arm64, x86_64                                                | Kotlin/Native cinterop (static library)    |
| **Linux**      | x86_64                                                       | Kotlin/Native cinterop (static library)    |
| **Windows**    | mingwX64                                                     | Kotlin/Native cinterop (static library)    |
| **tvOS**       | arm64, simulatorArm64                                        | Kotlin/Native cinterop (static library)    |
| **watchOS**    | arm64, simulatorArm64, deviceArm64                           | Kotlin/Native cinterop (static library)    |

## Gradle Dependency

**Kotlin Multiplatform / Android:**

```kotlin
implementation("cn.enaium.webrtc.resampler:webrtc-resampler-kmp:1.0.0")
```

> Built with Kotlin 2.4.10: consumers need a Kotlin 2.4+ compiler, since older ones cannot read the 2.4 metadata of the published artifacts.

**JVM:** the right native binary is resolved automatically — the `webrtc-resampler-kmp-jvm` artifact pulls in the matching `:jni-jvm-*` sibling on the classpath:

- `webrtc-resampler-kmp-jni-jvm-linux-x86_64`
- `webrtc-resampler-kmp-jni-jvm-linux-aarch64`
- `webrtc-resampler-kmp-jni-jvm-darwin-x86_64`
- `webrtc-resampler-kmp-jni-jvm-darwin-aarch64`
- `webrtc-resampler-kmp-jni-jvm-windows-x86_64`

`NativeLoader` detects `os.name`/`os.arch` at runtime, extracts the matching binary from the classpath to a temp directory, and `System.load`s it. No `java.library.path` setup is required for downstream JVM consumers.

## Which Resampler

| Binding                  | Input      | Ratios                                                       | Channels | Interface |
| ------------------------ | ---------- | ------------------------------------------------------------ | -------- | --------- |
| `Resampler`              | `ShortArray` | fixed WebRTC pairs only (8/16/32/44/48/96 kHz combinations) | 1 or 2   | push      |
| `PushSincResampler`      | `ShortArray`, `FloatArray` | arbitrary (44.1 kHz ⟷ 48 kHz, …)           | 1        | push      |
| `PushResamplerInt16` / `PushResamplerFloat` | `ShortArray` / `FloatArray` | arbitrary               | ≤ 8      | push      |
| `SincResampler`          | `FloatArray` | arbitrary                                                   | 1        | pull      |

`Resampler` rejects rate pairs its fixed filter bank cannot express; use one of the sinc resamplers for those (they cost more but are not limited to integer ratios). A `Resampler` is also the only resampler that is bit exact for 1:1 (same rate in and out).

## Quick Start

```kotlin
import cn.enaium.webrtc.resampler.createResampler
import cn.enaium.webrtc.resampler.createPushSincResampler

// Fixed pair, int16, 48 kHz -> 16 kHz, one 10 ms block at a time.
createResampler(48000, 16000, 1).use { resampler ->
    val input = ShortArray(480)   // 10 ms at 48 kHz
    val output = ShortArray(160)  // 10 ms at 16 kHz
    val written = resampler.resample(input, output)
    check(written == 160)
}

// Arbitrary ratio, float, 48 kHz -> 44.1 kHz.
createPushSincResampler(480, 441).use { resampler ->
    val input = FloatArray(480)
    val output = FloatArray(441)
    resampler.resample(input, output)
}
```

Every resampler is an `AutoCloseable` handle over native state; the block sizes are fixed at construction time and are what fixes the ratio (both sides cover the same duration, typically 10 ms). Buffers that do not match those sizes, and rate pairs the legacy resampler cannot express, are reported as `IllegalArgumentException` — the underlying `RTC_CHECK`s never fire.

## API Reference

```kotlin
fun createResampler(): Resampler
fun createResampler(inputSampleRate: Int, outputSampleRate: Int, channels: Int): Resampler
fun createSincResampler(
    ioSampleRateRatio: Double,
    requestFrames: Int,
    source: ResamplerSource
): SincResampler
fun createPushSincResampler(sourceFrames: Int, destinationFrames: Int): PushSincResampler
fun createPushResamplerInt16(sourceFramesPerChannel: Int, destinationFramesPerChannel: Int, channels: Int): PushResamplerInt16
fun createPushResamplerFloat(sourceFramesPerChannel: Int, destinationFramesPerChannel: Int, channels: Int): PushResamplerFloat
fun resamplerVersion(): String
fun sincResamplerKernelSize(): Int
fun pushSincAlgorithmicDelaySeconds(sourceRateHz: Int): Float
```

### Resampler

| Member                                        | Description                                             |
| --------------------------------------------- | ------------------------------------------------------- |
| `resample(input, output): Int`                | Resamples interleaved `ShortArray` samples, returns the number written |
| `reset(in, out, channels): Boolean`           | Reconfigures and resets all states                      |
| `resetIfNeeded(in, out, channels): Boolean`   | Same, but keeps the states when nothing changed          |
| `inputSampleRate`, `outputSampleRate`, `channels` | The current configuration                            |

### PushSincResampler

| Member                              | Description                                                    |
| ----------------------------------- | -------------------------------------------------------------- |
| `resample(source, destination): Int` | One block of `ShortArray` or `FloatArray` in, one block out      |
| `sourceFrames`, `destinationFrames`  | The block sizes the ratio was fixed from                        |

### PushResamplerInt16 / PushResamplerFloat

| Member                              | Description                                                     |
| ----------------------------------- | --------------------------------------------------------------- |
| `resample(input, output)`            | One interleaved block, `sourceFramesPerChannel * channels` in    |
| `sourceFramesPerChannel`, `destinationFramesPerChannel`, `channels` | The block geometry        |

### SincResampler

| Member                                | Description                                                   |
| ------------------------------------- | ------------------------------------------------------------- |
| `resample(frames, destination)`        | Pulls from the `ResamplerSource` and writes `frames` samples  |
| `chunkSize`, `requestFrames`           | Frames the source is asked for                                 |
| `flush()`                              | Drops buffered data and resets the internal indices            |
| `setRatio(ioSampleRateRatio)`          | Changes the ratio and rebuilds the kernels                     |

```kotlin
fun interface ResamplerSource {
    fun read(frames: Int, destination: FloatArray)
}
```

`read` is called on the thread that calls `SincResampler.resample` and must zero pad when fewer samples are available.

## Examples

Both examples drive the frozen API with 10 ms blocks, the block size WebRTC's pipeline is built around.

### [`examples/basic`](examples/basic/) — Android demo

A Jetpack Compose app that records from the microphone at 48 kHz, resamples to the selected output rate and plays the result back:

- **Engine selector** — `Resampler` (fixed WebRTC rate pairs) or `PushSincResampler` (arbitrary ratios, which is what makes 44.1 kHz selectable)
- **Output rate selector** — 8 / 16 / 32 / 44.1 / 48 kHz
- **Live statistics** — samples in and out, input and output RMS in dBFS, and audio time versus wall clock so a drifting or underrunning loop is visible; they sit behind the *Statistics* row, so only the controls stay on screen (failures such as a denied permission stay visible)

```bash
./gradlew :examples:basic:assembleDebug
```

### [`examples/waveform`](examples/waveform/) — resampling you can see and hear

A Kotlin Multiplatform app (JVM desktop, plus macOS/Linux/Windows native executables) that draws the signal before and after resampling with Dear ImGui + ImPlot in an SDL3 window:

- **Two traces over the same time span** — the source signal at the input rate and the resampled output at the output rate, so the ratio shows up as the different density of the traces
- **Audible result** — the resampled output is played through the output device, so a 48 kHz tone resampled to 8 kHz audibly loses its top end; when no device takes the output rate or none is present, the example reports it and keeps rendering
- **Controls** — source signal (tone, noise, sweep), input and output rate, engine, window length, play/stop
- **Statistics** — samples in and out, RMS and peak in dBFS and the measured level difference, in the *status* header together with the device, the engine, the block sizes and the library version; the header folds away, so only the controls and the plots stay on screen

```bash
./gradlew :examples:waveform:jvmRun                  # desktop JVM
./gradlew :examples:waveform:jvmRun --args="--frames 120"   # bounded headless run
```

Native executables (`./examples/waveform/build/bin/<target>/<mode>Executable/waveform.kexe`) take `RESAMPLER_KMP_FRAMES` to bound a headless run.

The viewer is built for the JVM and as native executables; Android is covered by [`examples/basic`](examples/basic/), which runs the binding through the AAR/JNI path.

## Building from Source

### Prerequisites

- JDK 17+
- CMake 3.16+
- Android SDK + NDK (for Android targets)
- Xcode command-line tools (for iOS/macOS/tvOS/watchOS targets)

### Clone with submodules

```bash
git clone --recursive https://github.com/Enaium/webrtc-resampler-kmp.git
cd webrtc-resampler-kmp
```

### Publish to Maven Local

```bash
./gradlew :resampler:publishToMavenLocal
```

### Run tests

```bash
./gradlew :resampler:jvmTest        # JVM (JNI)
./gradlew :resampler:macosArm64Test # macOS native (cinterop)
```

## Project Structure

```
webrtc-resampler-kmp/
├── webrtc-resampler/         # Git submodule (C++ library)
├── jni/
│   ├── CMakeLists.txt        # static library + JNI shared library build
│   ├── jni_bridge.cpp        # JNI bridge (C++ → JVM/Android)
│   ├── c_api/                # C API (webrtc_resampler_c.h/.cc)
│   └── jvm/                  # Per-OS/arch JNI publication subprojects
│       ├── darwin-aarch64, darwin-x86_64
│       ├── linux-x86_64, linux-aarch64
│       └── windows-x86_64
├── resampler/                # Kotlin Multiplatform module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/       # expect declarations + common interfaces
│       ├── commonTest/
│       ├── jvmMain/          # JVM actual (JNI) + NativeLoader
│       ├── androidMain/      # Android actual (JNI)
│       ├── nativeMain/       # Native actual (cinterop)
│       └── nativeInterop/cinterop/
├── examples/
│   ├── basic/                # Android demo (record → resample → play)
│   └── waveform/             # KMP ImGui/ImPlot before/after viewer
├── scripts/                  # Native build helpers
└── .github/workflows/        # publish + test
```

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file. The resampler itself is BSD 3-Clause, see the [submodule](webrtc-resampler/LICENSE).
