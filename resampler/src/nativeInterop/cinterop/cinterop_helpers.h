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
 * Helper header for Kotlin/Native cinterop.
 *
 * Provides struct definitions for the opaque types that are only forward
 * declared in the main C API header. They are used only by cinterop to generate
 * proper Kotlin types; the actual struct layout lives in the C++ implementation
 * and is never touched from Kotlin.
 */
#ifndef CINTEROP_HELPERS_H_
#define CINTEROP_HELPERS_H_

#include "webrtc_resampler_c.h"

/* Dummy struct definitions for cinterop type generation */
struct webrtc_resampler_t { void* impl; };
struct webrtc_resampler_sinc_t { void* impl; };
struct webrtc_resampler_push_sinc_t { void* impl; };
struct webrtc_resampler_push_s16_t { void* impl; };
struct webrtc_resampler_push_f32_t { void* impl; };

#endif /* CINTEROP_HELPERS_H_ */
