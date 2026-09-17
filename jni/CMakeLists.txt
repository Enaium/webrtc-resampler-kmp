cmake_minimum_required(VERSION 3.16)

project(webrtc_resampler C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_C_STANDARD 11)

# Builds the JNI shared library by default. Native (cinterop) targets build only
# the static library by passing -DBUILD_JNI=OFF.
option(BUILD_JNI "Build the JNI shared library" ON)

# ---- webrtc-resampler submodule + C API ----
set(WEBRTC_RESAMPLER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc-resampler)
set(C_API_DIR ${CMAKE_CURRENT_SOURCE_DIR}/c_api)

# =========================================================================
# Static library: the resampler sources and the C API over them.
# =========================================================================
file(GLOB_RECURSE RESAMPLER_SRC
    ${WEBRTC_RESAMPLER_DIR}/src/**/*.cc
    ${WEBRTC_RESAMPLER_DIR}/src/**/*.c
    ${WEBRTC_RESAMPLER_DIR}/src/**/*.cpp
    ${WEBRTC_RESAMPLER_DIR}/src/**/*.h
)

# The SIMD units are compiled per architecture, mirroring WebRTC's build
# configuration (see webrtc.gni and common_audio/BUILD.gn).
list(FILTER RESAMPLER_SRC EXCLUDE REGEX ".*sinc_resampler_(sse|avx2|neon)\\.cc$")

set(RESAMPLER_SSE_SRC ${WEBRTC_RESAMPLER_DIR}/src/common_audio/resampler/sinc_resampler_sse.cc)
set(RESAMPLER_AVX2_SRC ${WEBRTC_RESAMPLER_DIR}/src/common_audio/resampler/sinc_resampler_avx2.cc)
set(RESAMPLER_NEON_SRC ${WEBRTC_RESAMPLER_DIR}/src/common_audio/resampler/sinc_resampler_neon.cc)

add_library(webrtc_resampler STATIC)

if(MSVC)
    target_compile_options(webrtc_resampler PRIVATE /W3 /EHsc)
else()
    target_compile_options(webrtc_resampler PRIVATE -Wall -fPIC -Wno-deprecated -fexceptions)
endif()

if(ANDROID)
    target_compile_definitions(webrtc_resampler PRIVATE WEBRTC_ANDROID WEBRTC_POSIX)

    # rtc_base/cpu_info.cc calls android_getCpuFeatures() (it includes
    # <cpu-features.h>) and rtc_base/{checks,logging}.cc call __android_log_print.
    # The NDK ships the cpufeatures source; compiling it in keeps the shared
    # library free of an undefined android_getCpuFeatures, which would otherwise
    # only resolve when the process happens to have libandroid loaded.
    set(NDK_CPU_FEATURES_DIR ${ANDROID_NDK}/sources/android/cpufeatures)
    if(EXISTS ${NDK_CPU_FEATURES_DIR}/cpu-features.c)
        target_sources(webrtc_resampler PRIVATE ${NDK_CPU_FEATURES_DIR}/cpu-features.c)
        target_include_directories(webrtc_resampler PRIVATE ${NDK_CPU_FEATURES_DIR})
    else()
        target_include_directories(webrtc_resampler PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures)
    endif()
elseif(APPLE)
    target_compile_definitions(webrtc_resampler PRIVATE WEBRTC_MAC WEBRTC_POSIX)
elseif(WIN32)
    target_compile_definitions(webrtc_resampler PRIVATE WEBRTC_WIN)
    if(MINGW)
        target_compile_options(webrtc_resampler PRIVATE -mno-ms-bitfields)
        # MinGW-w64 cross-compilers don't add their sysroot include dir to the
        # default search path; add it explicitly so Windows.h and the
        # mingw-w64 libc headers resolve.
        target_include_directories(webrtc_resampler SYSTEM PRIVATE /usr/x86_64-w64-mingw32/include)
    endif()
elseif(UNIX)
    target_compile_definitions(webrtc_resampler PRIVATE WEBRTC_LINUX WEBRTC_POSIX)
endif()

# On Apple, CMAKE_SYSTEM_PROCESSOR reflects the host even when cross-compiling
# via CMAKE_OSX_ARCHITECTURES, so derive the effective arch from it first.
if(APPLE AND CMAKE_OSX_ARCHITECTURES)
    set(EFFECTIVE_ARCH ${CMAKE_OSX_ARCHITECTURES})
else()
    set(EFFECTIVE_ARCH ${CMAKE_SYSTEM_PROCESSOR})
endif()

message(STATUS "EFFECTIVE_ARCH: ${EFFECTIVE_ARCH}")

if(EFFECTIVE_ARCH MATCHES "x86_64|AMD64|amd64|x86|i686")
    list(APPEND RESAMPLER_SRC ${RESAMPLER_SSE_SRC})
    if(MSVC)
        if(CMAKE_SIZEOF_VOID_P EQUAL 4)
            set_source_files_properties(${RESAMPLER_SSE_SRC} PROPERTIES COMPILE_OPTIONS "/arch:SSE2")
        endif()
    else()
        set_source_files_properties(${RESAMPLER_SSE_SRC} PROPERTIES COMPILE_OPTIONS "-msse2")
    endif()

    # WebRTC enables the AVX2 runtime dispatch for Clang builds, but the
    # dispatch in sinc_resampler.cc is guarded by WEBRTC_ARCH_X86_FAMILY alone:
    # every x86 build refers to Convolve_AVX2, so the unit has to be compiled
    # for GCC (MinGW, Linux) and MSVC too - leaving it out is an undefined
    # reference, not a slower build. The kernel is only reached when
    # cpu_info::Supports() reports AVX2 and FMA3 at run time, so an older CPU
    # runs the SSE2 or C path.
    target_compile_definitions(webrtc_resampler PRIVATE WEBRTC_ENABLE_AVX2)
    list(APPEND RESAMPLER_SRC ${RESAMPLER_AVX2_SRC})
    if(MSVC)
        set_source_files_properties(${RESAMPLER_AVX2_SRC} PROPERTIES COMPILE_OPTIONS "/arch:AVX2")
    else()
        set_source_files_properties(${RESAMPLER_AVX2_SRC} PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
    endif()
elseif(EFFECTIVE_ARCH MATCHES "aarch64|arm64|ARM64|arm|ARM")
    target_compile_definitions(webrtc_resampler PRIVATE WEBRTC_HAS_NEON)
    list(APPEND RESAMPLER_SRC ${RESAMPLER_NEON_SRC})
endif()

target_sources(webrtc_resampler PRIVATE ${RESAMPLER_SRC})
target_sources(webrtc_resampler PRIVATE
    ${C_API_DIR}/webrtc_resampler_c.cc
    ${C_API_DIR}/webrtc_resampler_c.h
)

# PUBLIC: consumers of the cinterop klib (and the JNI library below) resolve
# the resampler headers through this include dir.
target_include_directories(webrtc_resampler PUBLIC
    ${WEBRTC_RESAMPLER_DIR}/src
    ${C_API_DIR}
)

set_target_properties(webrtc_resampler PROPERTIES LINKER_LANGUAGE CXX)

# Kotlin/Native links its Linux and Windows (mingwX64) binaries against the
# libstdc++ 6.0.25 that ships in its bundled gcc-8.3 target toolchain. That
# library predates std::__cxx11::basic_string::_M_replace_cold (a GCC 9
# addition that the host compiler emits for std::string append/assign here), so
# a static library built with the default ABI leaves lld with one undefined
# symbol: the user's program then fails to link. Building the library that gets
# embedded into the cinterop klibs against the classic std::string ABI keeps
# every symbol it references inside that sysroot - the same library still
# exports the 94 old-ABI string symbols.
#
# Only the Kotlin/Native builds (BUILD_JNI=OFF): the JNI shared libraries link
# the libstdc++ of the machine that runs them, and the Apple and Android native
# targets use libc++.
if(NOT MSVC AND NOT APPLE AND NOT ANDROID AND NOT BUILD_JNI)
    target_compile_definitions(webrtc_resampler PRIVATE _GLIBCXX_USE_CXX11_ABI=0)
endif()

# The extracted WebRTC headers are not self contained about <cstring> and
# <cstdint>; force-include them so the library compiles with libstdc++ 13+ as
# well (see the header for what is missing and where). MSVC spells the flag
# differently and, with the bundled STL, does not need it.
if(NOT MSVC)
    target_compile_options(webrtc_resampler PRIVATE
        -include ${C_API_DIR}/webrtc_resampler_compat.h
    )
endif()

# =========================================================================
# JNI shared library
# =========================================================================
if(BUILD_JNI)
    add_library(webrtc_resampler_jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp)
    set_target_properties(webrtc_resampler_jni PROPERTIES OUTPUT_NAME webrtc_resampler_jni)
    if(WIN32)
        # MinGW prefixes DLLs with "lib"; strip it so the artifact is
        # webrtc_resampler_jni.dll everywhere (NativeLoader expects no prefix).
        set_target_properties(webrtc_resampler_jni PROPERTIES PREFIX "")
    endif()

    if(ANDROID)
        find_library(log-lib log)
        if(log-lib)
            target_link_libraries(webrtc_resampler_jni PRIVATE ${log-lib})
        endif()
        # Align the .so to 16KB pages, required for 16KB page-size devices
        # (Android 15+); see https://developer.android.com/guide/practices/page-sizes.
        target_link_options(webrtc_resampler_jni PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384"
        )
    elseif(JNI_INCLUDE_DIR)
        target_include_directories(webrtc_resampler_jni PRIVATE ${JNI_INCLUDE_DIR})
        if(JNI_INCLUDE_DIR_PLATFORM)
            target_include_directories(webrtc_resampler_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
        endif()
    else()
        find_package(JNI REQUIRED)
        target_include_directories(webrtc_resampler_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()

    target_include_directories(webrtc_resampler_jni PRIVATE ${C_API_DIR})
    target_link_libraries(webrtc_resampler_jni PRIVATE webrtc_resampler)

    if(APPLE)
        target_link_libraries(webrtc_resampler_jni PRIVATE "-framework CoreFoundation")
    elseif(WIN32)
        # timeGetTime() used by rtc_base/time_utils.cc
        target_link_libraries(webrtc_resampler_jni PRIVATE winmm)
    endif()
endif()
