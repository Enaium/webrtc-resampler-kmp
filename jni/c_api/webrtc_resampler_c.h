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
 * C API over the WebRTC resampler (webrtc-resampler).
 *
 * One instance of this header is shared by both consumers of the library:
 *   * the JNI bridge (jni/jni_bridge.cpp) for JVM and Android,
 *   * Kotlin/Native's cinterop (resampler/src/nativeInterop/cinterop).
 *
 * Every function takes and returns plain C types; the C++ objects live behind
 * the opaque handles below.
 */

#ifndef WEBRTC_RESAMPLER_C_H_
#define WEBRTC_RESAMPLER_C_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* =========================================================================
 * Opaque handle types - the C++ objects are hidden behind these pointers
 * ========================================================================= */
typedef struct webrtc_resampler_t            webrtc_resampler_t;            /* webrtc::Resampler (int16) */
typedef struct webrtc_resampler_sinc_t       webrtc_resampler_sinc_t;       /* webrtc::SincResampler (pull, float) */
typedef struct webrtc_resampler_push_sinc_t  webrtc_resampler_push_sinc_t;  /* webrtc::PushSincResampler (mono) */
typedef struct webrtc_resampler_push_s16_t   webrtc_resampler_push_s16_t;   /* webrtc::PushResampler<int16_t> */
typedef struct webrtc_resampler_push_f32_t   webrtc_resampler_push_f32_t;   /* webrtc::PushResampler<float> */

/* Version of the wrapped library (webrtc-resampler). */
const char* webrtc_resampler_version(void);

/* =========================================================================
 * webrtc::Resampler - integer multi-rate resampler, int16 interleaved
 *
 * Handles the fixed rate pairs WebRTC supports (1:1, 1:2, 1:3, 1:4, 1:6,
 * 1:12, 2:3, 2:11, 4:11, 8:11, 11:16, 11:32 and their inverses - e.g. the
 * 8/16/32/44/48/96 kHz combinations, but neither 44 kHz <-> 48/96 kHz nor any
 * 44.1/22.05 kHz ratio). One or two channels.
 *
 * All functions return 0 on success and -1 on failure, like the C++ class.
 * ========================================================================= */
webrtc_resampler_t* webrtc_resampler_create(void);
webrtc_resampler_t* webrtc_resampler_create_reset(int in_freq,
                                                  int out_freq,
                                                  size_t num_channels);
void webrtc_resampler_destroy(webrtc_resampler_t* resampler);

/* Reset all states; -1 for an unsupported rate pair. */
int webrtc_resampler_reset(webrtc_resampler_t* resampler,
                           int in_freq,
                           int out_freq,
                           size_t num_channels);

/* Reset only if any parameter changed. */
int webrtc_resampler_reset_if_needed(webrtc_resampler_t* resampler,
                                     int in_freq,
                                     int out_freq,
                                     size_t num_channels);

/* Resamples `in_len` interleaved samples into `out`, writing the produced
 * (interleaved) sample count to `out_len`. `out_len` is untouched on failure.
 *
 * The produced count is a 32 bit value rather than `size_t` so that the
 * Kotlin/Native binding of this call is identical on 32 and 64 bit targets. */
int webrtc_resampler_push(webrtc_resampler_t* resampler,
                          const int16_t* in,
                          size_t in_len,
                          int16_t* out,
                          size_t max_len,
                          uint32_t* out_len);

/* =========================================================================
 * webrtc::PushSincResampler - mono, arbitrary ratio, one block in/out
 *
 * `source_frames` and `destination_frames` are the sizes of one block of the
 * same duration (typically 10 ms), which is what fixes the ratio.
 * Every call must pass exactly `source_frames` samples.
 * ========================================================================= */
webrtc_resampler_push_sinc_t* webrtc_resampler_push_sinc_create(
    size_t source_frames, size_t destination_frames);
void webrtc_resampler_push_sinc_destroy(webrtc_resampler_push_sinc_t* resampler);

/* Both return the number of samples written to `destination`
 * (always `destination_frames`). */
size_t webrtc_resampler_push_sinc_resample_s16(
    webrtc_resampler_push_sinc_t* resampler,
    const int16_t* source,
    size_t source_frames,
    int16_t* destination,
    size_t destination_capacity);

size_t webrtc_resampler_push_sinc_resample_f32(
    webrtc_resampler_push_sinc_t* resampler,
    const float* source,
    size_t source_frames,
    float* destination,
    size_t destination_capacity);

/* Delay of the filter kernel, in seconds, for a given source rate. */
float webrtc_resampler_push_sinc_algorithmic_delay_seconds(int source_rate_hz);

/* =========================================================================
 * webrtc::PushResampler<T> - multichannel (up to 8), interleaved
 *
 * Both use the same block contract as PushSincResampler: `src_samples_per_channel`
 * samples per channel go in, `dst_samples_per_channel` samples per channel come
 * out, and the caller must pass exactly those buffer sizes.
 * ========================================================================= */
webrtc_resampler_push_s16_t* webrtc_resampler_push_s16_create(
    size_t src_samples_per_channel,
    size_t dst_samples_per_channel,
    size_t num_channels);
void webrtc_resampler_push_s16_destroy(webrtc_resampler_push_s16_t* resampler);
void webrtc_resampler_push_s16_resample(webrtc_resampler_push_s16_t* resampler,
                                        const int16_t* source,
                                        int16_t* destination);

webrtc_resampler_push_f32_t* webrtc_resampler_push_f32_create(
    size_t src_samples_per_channel,
    size_t dst_samples_per_channel,
    size_t num_channels);
void webrtc_resampler_push_f32_destroy(webrtc_resampler_push_f32_t* resampler);
void webrtc_resampler_push_f32_resample(webrtc_resampler_push_f32_t* resampler,
                                        const float* source,
                                        float* destination);

/* =========================================================================
 * webrtc::SincResampler - pull interface
 *
 * `read_callback` is called (on the thread that calls
 * webrtc_resampler_sinc_resample) whenever the resampler needs more input; it
 * must fill `frames` samples into `destination` and is expected to zero pad
 * when fewer frames are available.
 * ========================================================================= */
typedef void (*webrtc_resampler_read_callback)(void* user_data,
                                               size_t frames,
                                               float* destination);

webrtc_resampler_sinc_t* webrtc_resampler_sinc_create(
    double io_sample_rate_ratio,
    size_t request_frames,
    webrtc_resampler_read_callback read_callback,
    void* user_data);
void webrtc_resampler_sinc_destroy(webrtc_resampler_sinc_t* resampler);

/* Resamples `frames` samples from the callback into `destination`. */
void webrtc_resampler_sinc_resample(webrtc_resampler_sinc_t* resampler,
                                    size_t frames,
                                    float* destination);

/* Maximum number of frames that guarantees a single callback per resample. */
size_t webrtc_resampler_sinc_chunk_size(const webrtc_resampler_sinc_t* resampler);

/* Kernel size of the sinc resampler; `request_frames` must be greater. */
size_t webrtc_resampler_sinc_kernel_size(void);

/* Frames requested from the callback per execution. */
size_t webrtc_resampler_sinc_request_frames(const webrtc_resampler_sinc_t* resampler);

/* Flush all buffered data and reset internal indices. */
void webrtc_resampler_sinc_flush(webrtc_resampler_sinc_t* resampler);

/* Update the input/output ratio; reconstructs the resampling kernels. */
void webrtc_resampler_sinc_set_ratio(webrtc_resampler_sinc_t* resampler,
                                     double io_sample_rate_ratio);

#ifdef __cplusplus
}
#endif

#endif /* WEBRTC_RESAMPLER_C_H_ */
