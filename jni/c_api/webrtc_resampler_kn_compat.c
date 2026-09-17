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

/*
 * Link shim for the Kotlin/Native Linux static library (see jni/CMakeLists.txt;
 * compiled only for the cinterop builds, never into the JNI shared libraries).
 *
 * glibc 2.32 added __libc_single_threaded, which the libstdc++ of a current
 * host compiler reads from the inline reference counting of the classic
 * std::string ABI (std::string::_Rep::_M_grab and friends call
 * __gnu_cxx::__is_single_threaded()). Kotlin/Native's Linux sysroot is glibc
 * 2.25, which has no such variable, so the reference stayed unresolved and lld
 * stopped with "undefined symbol: __libc_single_threaded".
 *
 * Zero means "might be multi threaded", i.e. the conservative answer: those
 * inline helpers take their locked path, which is correct either way. The
 * definition is weak, so on a system whose C library does define the variable
 * (any glibc 2.32+) the real one wins where the two are linked together.
 */

#if defined(__linux__) && !defined(__ANDROID__)
__attribute__((weak)) char __libc_single_threaded = 0;
#endif
