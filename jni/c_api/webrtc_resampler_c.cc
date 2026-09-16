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

#include "webrtc_resampler_c.h"

#include <cstddef>
#include <cstdint>
#include <new>

#include "api/audio/audio_view.h"
#include "common_audio/resampler/include/push_resampler.h"
#include "common_audio/resampler/include/resampler.h"
#include "common_audio/resampler/push_sinc_resampler.h"
#include "common_audio/resampler/sinc_resampler.h"

/* Kept in step with the webrtc-resampler submodule's CMake project version. */
#ifndef WEBRTC_RESAMPLER_VERSION
#define WEBRTC_RESAMPLER_VERSION "0.1.0"
#endif

/* =========================================================================
 * Handle definitions
 * ========================================================================= */

struct webrtc_resampler_t {
  webrtc::Resampler impl;
};

struct webrtc_resampler_push_sinc_t {
  webrtc::PushSincResampler impl;
  const size_t source_frames;

  webrtc_resampler_push_sinc_t(size_t source_frames, size_t destination_frames)
      : impl(source_frames, destination_frames), source_frames(source_frames) {}
};

struct webrtc_resampler_push_s16_t {
  webrtc::PushResampler<int16_t> impl;
  const size_t src_samples_per_channel;
  const size_t dst_samples_per_channel;
  const size_t num_channels;

  webrtc_resampler_push_s16_t(size_t src_samples_per_channel,
                              size_t dst_samples_per_channel,
                              size_t num_channels)
      : impl(src_samples_per_channel, dst_samples_per_channel, num_channels),
        src_samples_per_channel(src_samples_per_channel),
        dst_samples_per_channel(dst_samples_per_channel),
        num_channels(num_channels) {}
};

struct webrtc_resampler_push_f32_t {
  webrtc::PushResampler<float> impl;
  const size_t src_samples_per_channel;
  const size_t dst_samples_per_channel;
  const size_t num_channels;

  webrtc_resampler_push_f32_t(size_t src_samples_per_channel,
                              size_t dst_samples_per_channel,
                              size_t num_channels)
      : impl(src_samples_per_channel, dst_samples_per_channel, num_channels),
        src_samples_per_channel(src_samples_per_channel),
        dst_samples_per_channel(dst_samples_per_channel),
        num_channels(num_channels) {}
};

namespace {

/* Bridges the C callback to the C++ SincResamplerCallback interface. */
class ReadCallbackAdapter : public webrtc::SincResamplerCallback {
 public:
  ReadCallbackAdapter(webrtc_resampler_read_callback callback, void* user_data)
      : callback_(callback), user_data_(user_data) {}

  void Run(size_t frames, float* destination) override {
    callback_(user_data_, frames, destination);
  }

 private:
  webrtc_resampler_read_callback callback_;
  void* user_data_;
};

}  // namespace

struct webrtc_resampler_sinc_t {
  ReadCallbackAdapter callback;
  webrtc::SincResampler impl;

  webrtc_resampler_sinc_t(double io_sample_rate_ratio,
                          size_t request_frames,
                          webrtc_resampler_read_callback read_callback,
                          void* user_data)
      : callback(read_callback, user_data),
        impl(io_sample_rate_ratio, request_frames, &callback) {}
};

/* =========================================================================
 * Version
 * ========================================================================= */

const char* webrtc_resampler_version(void) {
  return WEBRTC_RESAMPLER_VERSION;
}

/* =========================================================================
 * webrtc::Resampler
 * ========================================================================= */

webrtc_resampler_t* webrtc_resampler_create(void) {
  return new (std::nothrow) webrtc_resampler_t();
}

webrtc_resampler_t* webrtc_resampler_create_reset(int in_freq,
                                                  int out_freq,
                                                  size_t num_channels) {
  webrtc_resampler_t* resampler = webrtc_resampler_create();
  if (resampler == nullptr) {
    return nullptr;
  }
  if (webrtc_resampler_reset(resampler, in_freq, out_freq, num_channels) != 0) {
    webrtc_resampler_destroy(resampler);
    return nullptr;
  }
  return resampler;
}

void webrtc_resampler_destroy(webrtc_resampler_t* resampler) {
  delete resampler;
}

int webrtc_resampler_reset(webrtc_resampler_t* resampler,
                           int in_freq,
                           int out_freq,
                           size_t num_channels) {
  return resampler->impl.Reset(in_freq, out_freq, num_channels);
}

int webrtc_resampler_reset_if_needed(webrtc_resampler_t* resampler,
                                     int in_freq,
                                     int out_freq,
                                     size_t num_channels) {
  return resampler->impl.ResetIfNeeded(in_freq, out_freq, num_channels);
}

int webrtc_resampler_push(webrtc_resampler_t* resampler,
                          const int16_t* in,
                          size_t in_len,
                          int16_t* out,
                          size_t max_len,
                          uint32_t* out_len) {
  size_t produced = 0;
  const int result = resampler->impl.Push(in, in_len, out, max_len, produced);
  if (result != 0) {
    return result;
  }
  *out_len = static_cast<uint32_t>(produced);
  return 0;
}

/* =========================================================================
 * webrtc::PushSincResampler
 * ========================================================================= */

webrtc_resampler_push_sinc_t* webrtc_resampler_push_sinc_create(
    size_t source_frames,
    size_t destination_frames) {
  return new (std::nothrow)
      webrtc_resampler_push_sinc_t(source_frames, destination_frames);
}

void webrtc_resampler_push_sinc_destroy(webrtc_resampler_push_sinc_t* resampler) {
  delete resampler;
}

size_t webrtc_resampler_push_sinc_resample_s16(
    webrtc_resampler_push_sinc_t* resampler,
    const int16_t* source,
    size_t source_frames,
    int16_t* destination,
    size_t destination_capacity) {
  return resampler->impl.Resample(source, source_frames, destination,
                                  destination_capacity);
}

size_t webrtc_resampler_push_sinc_resample_f32(
    webrtc_resampler_push_sinc_t* resampler,
    const float* source,
    size_t source_frames,
    float* destination,
    size_t destination_capacity) {
  return resampler->impl.Resample(source, source_frames, destination,
                                  destination_capacity);
}

float webrtc_resampler_push_sinc_algorithmic_delay_seconds(int source_rate_hz) {
  return webrtc::PushSincResampler::AlgorithmicDelaySeconds(source_rate_hz);
}

/* =========================================================================
 * webrtc::PushResampler<int16_t>
 * ========================================================================= */

webrtc_resampler_push_s16_t* webrtc_resampler_push_s16_create(
    size_t src_samples_per_channel,
    size_t dst_samples_per_channel,
    size_t num_channels) {
  return new (std::nothrow) webrtc_resampler_push_s16_t(
      src_samples_per_channel, dst_samples_per_channel, num_channels);
}

void webrtc_resampler_push_s16_destroy(webrtc_resampler_push_s16_t* resampler) {
  delete resampler;
}

void webrtc_resampler_push_s16_resample(webrtc_resampler_push_s16_t* resampler,
                                        const int16_t* source,
                                        int16_t* destination) {
  const size_t channels = resampler->num_channels;
  resampler->impl.Resample(
      webrtc::InterleavedView<const int16_t>(source,
                                             resampler->src_samples_per_channel,
                                             channels),
      webrtc::InterleavedView<int16_t>(destination,
                                       resampler->dst_samples_per_channel,
                                       channels));
}

/* =========================================================================
 * webrtc::PushResampler<float>
 * ========================================================================= */

webrtc_resampler_push_f32_t* webrtc_resampler_push_f32_create(
    size_t src_samples_per_channel,
    size_t dst_samples_per_channel,
    size_t num_channels) {
  return new (std::nothrow) webrtc_resampler_push_f32_t(
      src_samples_per_channel, dst_samples_per_channel, num_channels);
}

void webrtc_resampler_push_f32_destroy(webrtc_resampler_push_f32_t* resampler) {
  delete resampler;
}

void webrtc_resampler_push_f32_resample(webrtc_resampler_push_f32_t* resampler,
                                        const float* source,
                                        float* destination) {
  const size_t channels = resampler->num_channels;
  resampler->impl.Resample(
      webrtc::InterleavedView<const float>(source,
                                           resampler->src_samples_per_channel,
                                           channels),
      webrtc::InterleavedView<float>(destination,
                                     resampler->dst_samples_per_channel,
                                     channels));
}

/* =========================================================================
 * webrtc::SincResampler
 * ========================================================================= */

webrtc_resampler_sinc_t* webrtc_resampler_sinc_create(
    double io_sample_rate_ratio,
    size_t request_frames,
    webrtc_resampler_read_callback read_callback,
    void* user_data) {
  if (read_callback == nullptr) {
    return nullptr;
  }
  return new (std::nothrow) webrtc_resampler_sinc_t(
      io_sample_rate_ratio, request_frames, read_callback, user_data);
}

void webrtc_resampler_sinc_destroy(webrtc_resampler_sinc_t* resampler) {
  delete resampler;
}

void webrtc_resampler_sinc_resample(webrtc_resampler_sinc_t* resampler,
                                    size_t frames,
                                    float* destination) {
  resampler->impl.Resample(frames, destination);
}

size_t webrtc_resampler_sinc_chunk_size(const webrtc_resampler_sinc_t* resampler) {
  return resampler->impl.ChunkSize();
}

size_t webrtc_resampler_sinc_kernel_size(void) {
  return webrtc::SincResampler::kKernelSize;
}

size_t webrtc_resampler_sinc_request_frames(
    const webrtc_resampler_sinc_t* resampler) {
  return resampler->impl.request_frames();
}

void webrtc_resampler_sinc_flush(webrtc_resampler_sinc_t* resampler) {
  resampler->impl.Flush();
}

void webrtc_resampler_sinc_set_ratio(webrtc_resampler_sinc_t* resampler,
                                     double io_sample_rate_ratio) {
  resampler->impl.SetRatio(io_sample_rate_ratio);
}
