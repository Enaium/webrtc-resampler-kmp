import java.io.File
import java.util.Properties
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

val jniDir = rootProject.projectDir.resolve("jni")
val webrtcResamplerDir = rootProject.projectDir.resolve("webrtc-resampler")

val hostOs = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()

// ==================== Android NDK resolution ====================
// Resolved before canBuildNativeTarget (and therefore before the kotlin {}
// block) so that the androidNative targets can embed the NDK-cross-compiled
// static library on hosts that have the NDK installed.
val androidApiLevel = 21

val pinnedAndroidNdkVersion = "27.0.12077973"

fun resolveAndroidSdkDir(): java.io.File? {
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val localProps = rootProject.file("local.properties")
    if (localProps.isFile) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    return null
}

fun resolveAndroidNdkDir(): java.io.File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let {
            val f = file(it)
            if (f.isDirectory) return f
        }
    }
    val sdk = resolveAndroidSdkDir() ?: return null
    val ndkParent = sdk.resolve("ndk")
    if (!ndkParent.isDirectory) return null
    val pinned = ndkParent.resolve(pinnedAndroidNdkVersion)
    if (pinned.isDirectory) return pinned
    return ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

val resolvedAndroidNdk = resolveAndroidNdkDir()
val androidNdkToolchain = resolvedAndroidNdk?.resolve("build/cmake/android.toolchain.cmake")

// Whether the current host can cross-compile the C library for the given
// Kotlin/Native target. Apple targets build from macOS via Xcode; linuxX64 and
// mingwX64 build on Linux hosts (mingwX64 via the MinGW cross-compiler, which
// matches Kotlin/Native's own MinGW linker).
fun canBuildNativeTarget(targetName: String): Boolean = when (targetName) {
    "macosArm64", "macosX64",
    "iosArm64", "iosX64", "iosSimulatorArm64",
    "tvosArm64", "tvosSimulatorArm64",
    "watchosArm64", "watchosSimulatorArm64", "watchosDeviceArm64",
    -> hostOs.isMacOsX

    "linuxX64" -> hostOs.isLinux
    "mingwX64" -> hostOs.isLinux

    "androidNativeArm64", "androidNativeArm32", "androidNativeX64", "androidNativeX86",
    -> androidNdkToolchain?.isFile == true

    else -> false
}

// Resolved once in the root build (which also covers the Android SDK's copy,
// for machines that have no standalone cmake).
val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

kotlin {
    // ==================== JVM ====================
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // ==================== Android ====================
    android {
        namespace = "cn.enaium.webrtc.resampler"
        compileSdk = 37
        minSdk = 21

        withHostTest {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // ==================== WebAssembly ====================
    // Kotlin/Wasm has no cinterop: the resampler is compiled by Emscripten into
    // a standalone module (see the wasm section at the end of this file) that
    // the wasmJs actuals drive through a JS glue layer.
    wasmJs()

    // ==================== Native ====================
    macosArm64()
    macosX64()

    linuxX64()

    mingwX64()

    iosArm64()
    iosX64()
    iosSimulatorArm64()

    tvosArm64()
    tvosSimulatorArm64()

    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Android native (Kotlin/Native) targets, used by Android apps that link a
    // libmain.so instead of the JVM AAR.
    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // ==================== cinterop for all native targets ====================
    targets.withType<KotlinNativeTarget> {
        val targetName = this.name
        val canBuild = canBuildNativeTarget(targetName)
        compilations.getByName("main") {
            cinterops {
                create("webrtcResampler") {
                    // rtc_base/time_utils.cc calls timeGetTime on Windows, which
                    // lives in winmm; the def file records the option so that it
                    // travels with the published klib and the consumer's link
                    // finds it without extra configuration.
                    defFile(
                        project.file(
                            if (targetName == "mingwX64") {
                                "src/nativeInterop/cinterop/webrtc_resampler_mingw.def"
                            } else {
                                "src/nativeInterop/cinterop/webrtc_resampler.def"
                            },
                        ),
                    )
                    includeDirs(
                        project.file("src/nativeInterop/cinterop"),
                        rootProject.file("jni/c_api"),
                    )
                    if (canBuild) {
                        // Embed the per-target static library into the produced
                        // cinterop klib. Targets that can't be built on this host
                        // still get bindings (for klib publishing); the static
                        // library is built and embedded when building on the
                        // matching host.
                        val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
                        extraOpts(
                            "-libraryPath", outputDir.absolutePath,
                            "-staticLibrary", "libwebrtc_resampler.a",
                        )
                    }
                }
            }
            defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")
        }
    }

    // ==================== Source sets ====================
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Bundle all five JNI artifacts so consumers get the right
                // native binary out of the box; NativeLoader picks one at
                // runtime by os.name/os.arch.
                runtimeOnly(project(":jni-jvm-linux-x86_64"))
                runtimeOnly(project(":jni-jvm-linux-aarch64"))
                runtimeOnly(project(":jni-jvm-darwin-x86_64"))
                runtimeOnly(project(":jni-jvm-darwin-aarch64"))
                runtimeOnly(project(":jni-jvm-windows-x86_64"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        getByName("androidMain") {
            // Share the same JNI source code with JVM. On Android the
            // NativeLoader falls back to System.loadLibrary since the .so is
            // bundled inside the AAR's jniLibs.
            kotlin.srcDir("src/jvmMain/kotlin")
        }

        getByName("wasmJsMain") {
            dependencies {
                // Typed arrays (Int16Array, Float32Array) used to marshal the
                // sample buffers across the JS bridge into the module.
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

// ==================== Desktop JVM ====================
// The JNI library is provided by the :jni-jvm-{linux,darwin}-{x86_64,aarch64}
// subprojects. Their JARs ship libwebrtc_resampler_jni as a classpath resource
// that NativeLoader extracts at runtime - so :jvmTest needs no
// java.library.path tweak.
//
// Android host-test targets (Android unit tests running on the JVM) call
// System.loadLibrary directly from Resampler.jvm.kt, so they still need a flat
// directory containing libwebrtc_resampler_jni.{so,dylib}. We reuse the host's
// :jni-jvm-* subproject build output for that.
val hostJniProjectName = run {
    val arch = System.getProperty("os.arch").lowercase()
    val os = OperatingSystem.current()
    val archClassifier = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> null
    }
    val osClassifier = when {
        os.isMacOsX && archClassifier == "aarch64" -> "darwin-aarch64"
        os.isMacOsX -> "darwin-x86_64"
        os.isLinux && archClassifier == "aarch64" -> "linux-aarch64"
        os.isLinux -> "linux-x86_64"
        os.isWindows -> "windows-x86_64"
        else -> null
    }
    if (osClassifier != null) {
        ":jni-jvm-$osClassifier"
    } else {
        null
    }
}

if (hostJniProjectName != null) {
    val hostJniProject = project(hostJniProjectName)
    val hostNativeDir = hostJniProject.layout.buildDirectory.dir(
        "jni-native/${hostJniProjectName.removePrefix(":jni-jvm-")}",
    )

    tasks.withType<Test>().configureEach {
        if (name.contains("AndroidHostTest", ignoreCase = true)) {
            dependsOn("$hostJniProjectName:buildJniLibrary")
            systemProperty("java.library.path", hostNativeDir.get().asFile.absolutePath)
        }
    }
}

// ==================== Native: build static C library for each target ====================
fun registerNativeBuildTasks(targetName: String, cmakeFlags: List<String> = emptyList()) {
    val outputDir = layout.buildDirectory.dir("native/$targetName").get().asFile
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-native-$targetName").get().asFile
    val libraryFile = File(outputDir, "libwebrtc_resampler.a")

    val configureTask = tasks.register<Exec>("configureNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        doFirst {
            outputDir.mkdirs()
            cmakeBuildDir.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            listOf(
                cmakeExecutable, jniDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_JNI=OFF",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${outputDir.absolutePath}",
            ) + cmakeFlags,
        )
    }

    val buildTask = tasks.register<Exec>("buildNative_$targetName") {
        onlyIf { canBuildNativeTarget(targetName) }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
        inputs.dir(webrtcResamplerDir)
        inputs.dir(rootProject.file("jni/c_api"))
        inputs.file(rootProject.file("jni/CMakeLists.txt"))
        outputs.file(libraryFile)
    }

    tasks.matching {
        it.name.startsWith("cinteropWebrtcResampler") &&
            it.name.endsWith(targetName.replaceFirstChar { c -> c.uppercase() })
    }.configureEach {
        dependsOn(buildTask)
    }
}

if (hostOs.isMacOsX) {
    registerNativeBuildTasks(
        "macosArm64",
        listOf(
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
        ),
    )
    registerNativeBuildTasks(
        "macosX64",
        listOf(
            "-DCMAKE_OSX_ARCHITECTURES=x86_64",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
        ),
    )
    registerNativeBuildTasks(
        "iosArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=iphoneos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "iosX64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
            "-DCMAKE_OSX_ARCHITECTURES=x86_64",
            "-DCMAKE_OSX_SYSROOT=iphonesimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "iosSimulatorArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=iOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=iphonesimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "tvosArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=tvOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=appletvos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "tvosSimulatorArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=tvOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=appletvsimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        ),
    )
    registerNativeBuildTasks(
        "watchosArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=watchOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=watchos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
        ),
    )
    registerNativeBuildTasks(
        "watchosSimulatorArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=watchOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=watchsimulator",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
        ),
    )
    registerNativeBuildTasks(
        "watchosDeviceArm64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=watchOS",
            "-DCMAKE_SYSTEM_PROCESSOR=arm64",
            "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=watchos",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=5.0",
        ),
    )
} else if (hostOs.isLinux) {
    registerNativeBuildTasks("linuxX64")
    // mingwX64 is cross-compiled with MinGW-w64; install it with
    // `sudo apt-get install gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64`.
    // CMAKE_SYSTEM_NAME=Windows makes CMake cross-compile; the MinGW sysroot
    // at /usr/x86_64-w64-mingw32 provides Windows.h and the CRT.
    registerNativeBuildTasks(
        "mingwX64",
        listOf(
            "-DCMAKE_SYSTEM_NAME=Windows",
            "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
            "-DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc",
            "-DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++",
            "-DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres",
        ),
    )
}

// ==================== Android native (Kotlin/Native) static libraries ====================
// Cross-compiled with the NDK toolchain for the androidNative targets; the
// resulting libwebrtc_resampler.a is embedded into each target's cinterop klib,
// so a Kotlin/Native Android app (libmain.so) links the resampler statically.
androidNdkToolchain?.takeIf { it.isFile }?.let { toolchain ->
    listOf(
        "androidNativeArm64" to "arm64-v8a",
        "androidNativeArm32" to "armeabi-v7a",
        "androidNativeX64" to "x86_64",
        "androidNativeX86" to "x86",
    ).forEach { (targetName, abi) ->
        registerNativeBuildTasks(
            targetName,
            listOf(
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-$androidApiLevel",
            ),
        )
    }
}

// ==================== Android: build JNI shared library per ABI ====================
val androidJniAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val androidJniLibsDir = layout.buildDirectory.dir("jniLibs")

val buildAndroidJniLibs by tasks.registering {
    group = "build"
    description = "Builds the JNI shared library for all Android ABIs."
}

androidJniAbis.forEach { abi ->
    val outputDir = layout.buildDirectory.dir("jniLibs/$abi")
    val cmakeBuildDir = layout.buildDirectory.dir("cmake-android-$abi").get().asFile

    val configureTask = tasks.register<Exec>("configureAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        doFirst {
            cmakeBuildDir.mkdirs()
            outputDir.get().asFile.mkdirs()
        }
        workingDir = cmakeBuildDir
        commandLine(
            cmakeExecutable, jniDir.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_TOOLCHAIN_FILE=${androidNdkToolchain?.absolutePath ?: ""}",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-$androidApiLevel",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${outputDir.get().asFile.absolutePath}",
        )
    }

    val buildTask = tasks.register<Exec>("buildAndroidJni_$abi") {
        onlyIf { androidNdkToolchain?.isFile == true }
        dependsOn(configureTask)
        workingDir = cmakeBuildDir
        commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
        inputs.dir(webrtcResamplerDir)
        inputs.dir(rootProject.file("jni/c_api"))
        inputs.file(rootProject.file("jni/CMakeLists.txt"))
        outputs.file(outputDir.map { it.file("libwebrtc_resampler_jni.so") })
    }

    buildAndroidJniLibs.configure { dependsOn(buildTask) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            androidJniLibsDir.get().asFile.absolutePath,
        )
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildAndroidJniLibs) }

// ==================== wasm: build the resampler module for the wasmJs target ====================
// Kotlin/Wasm has no cinterop and cannot embed C libraries, so the resampler is
// compiled to a standalone Emscripten module (webrtc_resampler_wasm.js +
// .wasm) that the wasmJs actuals drive through a JS glue layer (see
// wasm/webrtc_resampler_kmp_glue.js). The module and the glue ship as an npm
// package instead of a Maven "assets" jar: web apps depend on it like any other
// npm package and import initWebrtcResamplerKmp() before running the Kotlin/Wasm
// module, so no asset extraction step is involved.
//
// The C++ build reuses jni/CMakeLists.txt (BUILD_JNI=OFF): same source list,
// same platform defines, only the toolchain differs.

fun emsdkPath(): String {
    System.getenv("EMSDK")?.takeIf { it.isNotBlank() }?.let { return it }
    providers.gradleProperty("wasm.emsdk").orNull?.takeIf { it.isNotBlank() }?.let { return it }
    val home = System.getProperty("user.home")
    listOf(File(home, "emsdk"), File(home, "sdk/emsdk"))
        .forEach { if (it.isDirectory()) return it.absolutePath }
    return ""
}

val wasmEmsdk = emsdkPath()
val wasmEmxx = File(wasmEmsdk, "upstream/emscripten/em++")
val wasmToolchain = File(wasmEmsdk, "upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake")
val wasmDir = projectDir.resolve("wasm")
val wasmOutput = layout.buildDirectory.dir("wasm").get().asFile

// Emscripten requires Python >= 3.10; the emsdk ships its own interpreter,
// which may not be on PATH (e.g. macOS ships Python 3.9 as /usr/bin/python3).
fun wasmEnv(): Map<String, String> {
    val pythonDir = File(wasmEmsdk, "python").listFiles()?.firstOrNull { it.isDirectory }
    return if (pythonDir != null) {
        mapOf("PATH" to "${pythonDir.absolutePath}/bin:${System.getenv("PATH")}")
    } else {
        emptyMap()
    }
}

val configureWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures the resampler static library for wasm."
    onlyIf { wasmEmsdk.isNotEmpty() }
    doFirst {
        File(wasmOutput, "cmake").mkdirs()
        File(wasmOutput, "lib").mkdirs()
    }
    workingDir = File(wasmOutput, "cmake")
    environment(wasmEnv())
    // A toolchain file disables the generator's normal make discovery, so the
    // make program must be passed explicitly (emcmake does the same).
    val makeProgram = if (hostOs.isWindows) "nmake" else "/usr/bin/make"
    commandLine(
        cmakeExecutable, jniDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_TOOLCHAIN_FILE=${wasmToolchain.absolutePath}",
        "-DCMAKE_MAKE_PROGRAM=$makeProgram",
        "-DBUILD_JNI=OFF",
        "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${File(wasmOutput, "lib").absolutePath}",
    )
}

val buildWasmLib by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the resampler static library for wasm."
    onlyIf { wasmEmsdk.isNotEmpty() }
    dependsOn(configureWasm)
    workingDir = File(wasmOutput, "cmake")
    environment(wasmEnv())
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    inputs.dir(webrtcResamplerDir)
    inputs.dir(rootProject.file("jni/c_api"))
    inputs.file(rootProject.file("jni/CMakeLists.txt"))
    outputs.file(File(wasmOutput, "lib/libwebrtc_resampler.a"))
}

val linkWasmResampler by tasks.registering(Exec::class) {
    group = "build"
    description = "Links the Emscripten resampler module (wasm + JS)."
    onlyIf { wasmEmsdk.isNotEmpty() }
    dependsOn(buildWasmLib)
    workingDir = wasmDir
    environment(wasmEnv())
    doFirst {
        wasmOutput.mkdirs()
        commandLine(
            wasmEmxx.absolutePath,
            File(wasmOutput, "lib/libwebrtc_resampler.a").absolutePath,
            "-o", File(wasmOutput, "webrtc_resampler_wasm.js").absolutePath,
            "-sMODULARIZE=1",
            "-sEXPORT_NAME=WebrtcResamplerModule",
            "-sEXPORT_ES6=1",
            // The glue needs the C API and the module's typed array views.
            "-sEXPORTED_FUNCTIONS=" + WASM_EXPORTS.joinToString(",") { "_$it" } + ",_malloc,_free",
            "-sEXPORTED_RUNTIME_METHODS=cwrap,UTF8ToString,lengthBytesUTF8,HEAP8,HEAP16,HEAP32,HEAPU32,HEAPF32,HEAPU8",
            "-sALLOW_MEMORY_GROWTH=1",
            "-sINITIAL_MEMORY=16777216",
            // Node keeps the CI smoke test and local checks working.
            "-sENVIRONMENT=web,worker,node",
            // The resampler is compiled with -fexceptions; without this the
            // runtime would abort on the first throw (std::string and friends).
            "-sDISABLE_EXCEPTION_CATCHING=0",
            "--no-entry",
        )
    }
    inputs.dir(webrtcResamplerDir)
    inputs.dir(rootProject.file("jni/c_api"))
    outputs.file(File(wasmOutput, "webrtc_resampler_wasm.js"))
    outputs.file(File(wasmOutput, "webrtc_resampler_wasm.wasm"))
}

/** C API functions the wasmJs actuals call through the glue. */
val WASM_EXPORTS = listOf(
    "webrtc_resampler_version",
    "webrtc_resampler_create",
    "webrtc_resampler_create_reset",
    "webrtc_resampler_destroy",
    "webrtc_resampler_reset",
    "webrtc_resampler_reset_if_needed",
    "webrtc_resampler_push",
    "webrtc_resampler_push_sinc_create",
    "webrtc_resampler_push_sinc_destroy",
    "webrtc_resampler_push_sinc_resample_s16",
    "webrtc_resampler_push_sinc_resample_f32",
    "webrtc_resampler_push_sinc_algorithmic_delay_seconds",
    "webrtc_resampler_push_s16_create",
    "webrtc_resampler_push_s16_destroy",
    "webrtc_resampler_push_s16_resample",
    "webrtc_resampler_push_f32_create",
    "webrtc_resampler_push_f32_destroy",
    "webrtc_resampler_push_f32_resample",
    "webrtc_resampler_sinc_kernel_size",
)

val wasmNpmDir = layout.buildDirectory.dir("npm").get().asFile

val assembleWasmNpm by tasks.registering {
    group = "build"
    description = "Stages the @enaium/webrtc-resampler-kmp npm package in build/npm."
    dependsOn(linkWasmResampler)
    inputs.file(wasmDir.resolve("webrtc_resampler_kmp_glue.js"))
    // The version comes from the Gradle version, so a bump must re-stage.
    inputs.property("npmPackageVersion", project.version.toString())
    inputs.file(File(wasmOutput, "webrtc_resampler_wasm.js"))
    inputs.file(File(wasmOutput, "webrtc_resampler_wasm.wasm"))
    outputs.dir(wasmNpmDir)
    doLast {
        wasmNpmDir.deleteRecursively()
        wasmNpmDir.mkdirs()
        wasmDir.resolve("webrtc_resampler_kmp_glue.js")
            .copyTo(wasmNpmDir.resolve("webrtc_resampler_kmp_glue.js"), overwrite = true)
        File(wasmOutput, "webrtc_resampler_wasm.js")
            .copyTo(wasmNpmDir.resolve("webrtc_resampler_wasm.js"), overwrite = true)
        File(wasmOutput, "webrtc_resampler_wasm.wasm")
            .copyTo(wasmNpmDir.resolve("webrtc_resampler_wasm.wasm"), overwrite = true)
        wasmNpmDir.resolve("package.json").writeText(
            """
            {
              "name": "@enaium/webrtc-resampler-kmp",
              "version": "${project.version}",
              "description": "Emscripten-compiled WebRTC resampler module and JS glue for the webrtc-resampler-kmp wasmJs target",
              "license": "MIT",
              "type": "module",
              "exports": {
                ".": "./webrtc_resampler_kmp_glue.js",
                "./webrtc_resampler_wasm.js": "./webrtc_resampler_wasm.js",
                "./package.json": "./package.json"
              },
              "files": ["webrtc_resampler_kmp_glue.js", "webrtc_resampler_wasm.js", "webrtc_resampler_wasm.wasm"],
              "homepage": "https://github.com/Enaium/webrtc-resampler-kmp",
              "repository": {
                "type": "git",
                "url": "git+https://github.com/Enaium/webrtc-resampler-kmp.git"
              }
            }
            """.trimIndent() + "\n",
        )
    }
}

val runWasmSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the Node smoke test against the staged wasm module."
    dependsOn(assembleWasmNpm)
    workingDir = wasmDir
    commandLine("node", "smoke.mjs", wasmNpmDir.absolutePath)
}

val publishWasmNpm by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Publishes @enaium/webrtc-resampler-kmp to npm (token: npm.publish.key)."
    dependsOn(assembleWasmNpm)
    workingDir = wasmNpmDir
    // The token lives in the user-level Gradle properties file
    // (~/.gradle/gradle.properties, next to mavenCentralUsername and the
    // signing.* entries); CI writes the same key there from a repository
    // secret. `-Pnpm.publish.key=<token>` overrides it for one-off runs.
    val npmKey = providers.gradleProperty("npm.publish.key")
    doFirst {
        val key = npmKey.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "npm.publish.key is not set: add it to ~/.gradle/gradle.properties " +
                    "or pass -Pnpm.publish.key=<token>",
            )
        // NODE_AUTH_TOKEN is expanded by npm through the environment, so the
        // token never lands in the package.json/tarball; .npmrc is on npm's
        // never-publish list anyway.
        environment("NODE_AUTH_TOKEN", key)
        wasmNpmDir.resolve(".npmrc").writeText(
            "//registry.npmjs.org/:_authToken=\${NODE_AUTH_TOKEN}\n",
        )
    }
    // The version carries a prerelease suffix (1.0.1-rolling.<tag>), so npm
    // requires an explicit tag; `latest` keeps the bare package name resolved.
    commandLine("npm", "publish", "--access", "public", "--tag", "latest")
}

// ==================== Publishing ====================
// Version is read from VERSION_NAME in gradle.properties (or -Pversion).
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "webrtc-resampler-kmp",
        // null -> the plugin falls back to project.version (VERSION_NAME in
        // gradle.properties or -Pversion on the command line)
        version = null,
    )

    pom {
        name.set("webrtc-resampler-kmp")
        description.set(
            "Kotlin Multiplatform bindings for the WebRTC resampler.",
        )
        url.set("https://github.com/Enaium/webrtc-resampler-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("BSD-3-Clause")
                url.set("https://opensource.org/licenses/BSD-3-Clause")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/webrtc-resampler-kmp")
            connection.set("scm:git:git@github.com:Enaium/webrtc-resampler-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/webrtc-resampler-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/webrtc-resampler-kmp/issues")
        }
    }
}
