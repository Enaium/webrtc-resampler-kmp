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
 * Force-included into every translation unit of the resampler library (see
 * jni/CMakeLists.txt); it is not part of the C API and nothing includes it.
 *
 * The extracted WebRTC headers are not self contained about two standard
 * headers, which only shows up on toolchains that stopped providing them
 * transitively:
 *
 *   - api/audio/audio_view.h calls memcpy() (in MonoView::CopyTo and
 *     CopySamples) without including <cstring>. libstdc++ up to 12 and libc++
 *     happen to include it through <algorithm>/<vector>, libstdc++ 13+ does
 *     not, so GCC 13 and newer fail with "'memcpy' was not declared in this
 *     scope" while building PushResampler<T>.
 *   - rtc_base/checks.h, rtc_base/logging.h, rtc_base/numerics/safe_minmax.h
 *     and api/audio/audio_view.h use the fixed width integer types without
 *     including <cstdint>.
 */

#ifndef WEBRTC_RESAMPLER_COMPAT_H_
#define WEBRTC_RESAMPLER_COMPAT_H_

#ifdef __cplusplus
#include <cstdint>
#include <cstring>
#endif

#endif /* WEBRTC_RESAMPLER_COMPAT_H_ */
