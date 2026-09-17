import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// ---------------------------------------------------------------------------
// MinGW GUID definitions
//
// audio-io-kmp's WASAPI bindings reference the MMDevice and IAudioClient GUIDs
// and ask for -lole32 -loleaut32 -luuid -lavrt -lksuser. No import library in
// Kotlin/Native's MinGW sysroot defines those GUIDs (libuuid.a does not carry
// them and there is no libmmdevapi.a), so linking a mingwX64 binary stopped at
// "undefined symbol: CLSID_MMDeviceEnumerator". The translation unit compiled
// here defines them - <initguid.h> turns the headers' DEFINE_GUID declarations
// into definitions, the same ones an MSVC build takes from uuid.lib - and the
// HID functions the device lookup calls come from the sysroot's libhid.a.
// ---------------------------------------------------------------------------

val mingwGuidsSource = file("src/mingwX64Main/c/wasapi_guids.c")

val mingwGuidsObject = layout.buildDirectory.file("mingw-guids/wasapi_guids.o")

val compileMingwGuids by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the Windows GUID definitions the mingwX64 link needs."
    inputs.file(mingwGuidsSource)
    outputs.file(mingwGuidsObject)
    doFirst { mingwGuidsObject.get().asFile.parentFile.mkdirs() }
    workingDir = projectDir
    commandLine(
        // The sysroot Kotlin/Native links mingwX64 against has no compiler of
        // its own; this is the one the CI runner and MSYS2 install.
        "x86_64-w64-mingw32-gcc",
        "-c", mingwGuidsSource.relativeTo(projectDir).path,
        "-o", mingwGuidsObject.get().asFile.absolutePath,
    )
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.webrtc.resampler.examples.waveform.Main_jvmKt"
        }
    }

    // Native targets every dependency publishes klibs for (resampler,
    // imgui-kmp, sdl-kmp and audio-io-kmp all ship macOS, Linux x86_64 and
    // Windows klibs, so the same UI source builds into a standalone executable
    // on each of them).
    macosArm64 {
        binaries.executable()
    }

    macosX64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable {
            // The GUID definitions of this module and the HID import library
            // audio-io-kmp's WASAPI device lookup needs; see the top of the file.
            linkerOpts(mingwGuidsObject.get().asFile.absolutePath, "-lhid")
        }
    }

    // The repository disables the automatic default hierarchy template
    // (gradle.properties), so materialize it here: the JVM and native targets
    // need their shared intermediate source sets (nativeMain, appleMain, ...)
    // for the common UI code and the entry points.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":resampler"))

                // Dear ImGui + ImPlot bindings and the SDL3 window/renderer
                // they are driven from; audio-io-kmp plays the resampled PCM.
                implementation(libs.imgui.kmp)
                implementation(libs.sdl.kmp)
                implementation(libs.audio.io.kmp)
            }
        }
    }
}

// The mingwX64 link takes the object the task above produces on its link line,
// so it has to exist first.
listOf("linkDebugExecutableMingwX64", "linkReleaseExecutableMingwX64").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach { dependsOn(compileMingwGuids) }
}

// SDL3 has to own the first thread on macOS, otherwise video driver init fails
// with "No available video device". Mirrors the imgui-kmp examples.
// --enable-native-access silences the JNI warnings on JDK 24+.
// Native executables need none of this: their main already runs on the thread
// SDL owns.
tasks.withType<JavaExec>().configureEach {
    if (OperatingSystem.current().isMacOsX) {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
