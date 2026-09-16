/*
 * Per-OS/arch JNI artifact: windows-x86_64.
 * Ships webrtc_resampler_jni.dll as a classpath resource at
 * /cn/enaium/webrtc/resampler/native/windows-x86_64/, which NativeLoader
 * (in :resampler's jvmMain) extracts and System.load()s at runtime.
 */
import org.gradle.internal.os.OperatingSystem

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val jniOs = "windows"
val jniArch = "x86_64"
val classifier = "$jniOs-$jniArch"
val libFile = "webrtc_resampler_jni.dll"
val resourceDir = "cn/enaium/webrtc/resampler/native/$classifier"

val host = OperatingSystem.current()
val hostArch = System.getProperty("os.arch").lowercase()
val canBuildHere = host.isWindows && (hostArch == "amd64" || hostArch == "x86_64")

// On Windows the MinGW toolchain is used (git-bash ships with it, or install
// via MSYS2). MSVC is avoided because the webrtc-resampler C++ code relies on
// GCC/Clang extensions.
val makeGenerator = if (System.getenv("MSYSTEM") != null) "MSYS Makefiles" else "MinGW Makefiles"

// Resolved once in the root build: an IDE-launched Gradle daemon has a minimal
// PATH, on which `cmake` is not found.
val cmakeExecutable: String = rootProject.extra["cmakeExecutable"] as String

val nativeOutputDir = layout.buildDirectory.dir("jni-native/$classifier")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jni/$classifier")

val configureJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "cmake-configures webrtc_resampler_jni for $classifier."
    onlyIf { canBuildHere }
    val outDir = nativeOutputDir.get().asFile
    val buildDir = cmakeBuildDir.get().asFile
    doFirst {
        outDir.mkdirs()
        buildDir.mkdirs()
    }
    workingDir = buildDir
    val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME") ?: ""
    val jniInclude = if (javaHome.isNotEmpty()) "$javaHome/include" else ""
    commandLine(
        cmakeExecutable,
        rootProject.file("jni").absolutePath,
        "-G", makeGenerator,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJNI_INCLUDE_DIR=$jniInclude",
        "-DJNI_INCLUDE_DIR_PLATFORM=$jniInclude/win32",
        // DLLs are RUNTIME outputs in CMake, not LIBRARY outputs.
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${{outDir.absolutePath}}",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${{outDir.absolutePath}}",
        // Statically link the MinGW runtime so the DLL has no dependency on
        // libstdc++-6.dll / libgcc_s_seh-1.dll, which are not on the JVM's
        // PATH. (-static-libwinpthread is not supported by all MinGW builds;
        // winpthread-1.dll is only needed when std::thread is used.)
        "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++",
    )
}

val buildJniLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds webrtc_resampler_jni.dll for $classifier."
    onlyIf { canBuildHere }
    dependsOn(configureJniLibrary)
    workingDir = cmakeBuildDir.get().asFile
    commandLine(cmakeExecutable, "--build", ".", "--config", "Release")
    inputs.files(rootProject.file("jni/CMakeLists.txt"), rootProject.file("jni/jni_bridge.cpp"))
    inputs.dir(rootProject.file("jni/c_api"))
    inputs.dir(rootProject.file("webrtc-resampler"))
    outputs.file(nativeOutputDir.map { it.file(libFile) })
}

tasks.named<Copy>("processResources") {
    dependsOn(buildJniLibrary)
    // Use the build task's declared outputs (lazily resolved at execution
    // time) instead of the directory Provider, which may be snapshotted
    // empty at configuration time.
    from(buildJniLibrary.map { it.outputs.files }) {
        include(libFile)
        into(resourceDir)
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = rootProject.group.toString(),
        artifactId = "webrtc-resampler-kmp-jni-jvm-$classifier",
        version = rootProject.version.toString(),
    )
    pom {
        name.set("webrtc-resampler-kmp-jni-jvm-$classifier")
        description.set(
            "Prebuilt JNI shared library for webrtc-resampler-kmp on $jniOs/$jniArch. " +
                "Loaded automatically by NativeLoader; not intended to be depended on directly.",
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
