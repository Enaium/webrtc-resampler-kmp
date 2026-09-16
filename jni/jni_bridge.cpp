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
 * JNI bridge over the resampler C API (jni/c_api/webrtc_resampler_c.h).
 *
 * Every function maps one to one onto a method of the Kotlin object
 * cn.enaium.webrtc.resampler.Jni; the Kotlin side validates buffer sizes and
 * rate pairs before calling in, so the underlying RTC_CHECKs never fire.
 */

#include <jni.h>

#include <cstddef>
#include <new>

#include "webrtc_resampler_c.h"

namespace {

inline webrtc_resampler_t* Resampler(jlong ptr) {
  return reinterpret_cast<webrtc_resampler_t*>(ptr);
}

inline webrtc_resampler_push_sinc_t* PushSinc(jlong ptr) {
  return reinterpret_cast<webrtc_resampler_push_sinc_t*>(ptr);
}

/*
 * Receives the samples the pull-based SincResampler asks for and forwards the
 * request to a Kotlin ResamplerSource.
 *
 * SincResamplerCallback::Run() runs on the thread that called Resample(), i.e.
 * the thread the JNI call comes in on, so the JNIEnv captured for the duration
 * of that call is the right one.
 */
class JvmSincSource {
 public:
  JvmSincSource(JNIEnv* env, jobject source) {
    target_ = env->NewGlobalRef(source);
    jclass cls = env->GetObjectClass(source);
    read_ = env->GetMethodID(cls, "read", "(I[F)V");
    env->DeleteLocalRef(cls);
  }

  JvmSincSource(const JvmSincSource&) = delete;
  JvmSincSource& operator=(const JvmSincSource&) = delete;

  bool valid() const { return target_ != nullptr && read_ != nullptr; }

  void release(JNIEnv* env) {
    if (target_ != nullptr) {
      env->DeleteGlobalRef(target_);
      target_ = nullptr;
    }
  }

  void run(JNIEnv* env, size_t frames, float* destination) {
    const jsize length = static_cast<jsize>(frames);
    jfloatArray array = env->NewFloatArray(length);
    if (array == nullptr) {
      return;
    }
    env->CallVoidMethod(target_, read_, static_cast<jint>(frames), array);
    if (env->ExceptionCheck()) {
      // Let the Kotlin exception surface once this native call returns; the
      // resampler keeps running on silence until then.
      for (jsize i = 0; i < length; ++i) {
        destination[i] = 0.0f;
      }
    } else {
      env->GetFloatArrayRegion(array, 0, length, destination);
    }
    env->DeleteLocalRef(array);
  }

 private:
  jobject target_ = nullptr;
  jmethodID read_ = nullptr;
};

/* Handle for a pull resampler: the C handle plus the Kotlin source it pulls from. */
struct JvmSincHandle {
  webrtc_resampler_sinc_t* resampler = nullptr;
  JvmSincSource* source = nullptr;
  /* The JNIEnv of the resample call in flight; set on every entry point. */
  JNIEnv* env = nullptr;
};

void JvmSincReadTrampoline(void* user_data, size_t frames, float* destination) {
  auto* handle = static_cast<JvmSincHandle*>(user_data);
  handle->source->run(handle->env, frames, destination);
}

}  // namespace

/* =========================================================================
 * Version
 * ========================================================================= */

extern "C" JNIEXPORT jstring JNICALL
Java_cn_enaium_webrtc_resampler_Jni_version(JNIEnv* env, jobject thiz) {
  return env->NewStringUTF(webrtc_resampler_version());
}

/* =========================================================================
 * webrtc::Resampler
 * ========================================================================= */

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_resampler_Jni_resamplerCreate(JNIEnv* env, jobject thiz) {
  return reinterpret_cast<jlong>(webrtc_resampler_create());
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_resampler_Jni_resamplerCreateReset(JNIEnv* env,
                                                         jobject thiz,
                                                         jint in_freq,
                                                         jint out_freq,
                                                         jint channels) {
  auto* resampler = webrtc_resampler_create_reset(
      in_freq, out_freq, static_cast<size_t>(channels));
  return reinterpret_cast<jlong>(resampler);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_resamplerDestroy(JNIEnv* env,
                                                     jobject thiz,
                                                     jlong ptr) {
  webrtc_resampler_destroy(Resampler(ptr));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_enaium_webrtc_resampler_Jni_resamplerReset(JNIEnv* env,
                                                   jobject thiz,
                                                   jlong ptr,
                                                   jint in_freq,
                                                   jint out_freq,
                                                   jint channels) {
  const int result = webrtc_resampler_reset(
      Resampler(ptr), in_freq, out_freq, static_cast<size_t>(channels));
  return result == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_enaium_webrtc_resampler_Jni_resamplerResetIfNeeded(JNIEnv* env,
                                                           jobject thiz,
                                                           jlong ptr,
                                                           jint in_freq,
                                                           jint out_freq,
                                                           jint channels) {
  const int result = webrtc_resampler_reset_if_needed(
      Resampler(ptr), in_freq, out_freq, static_cast<size_t>(channels));
  return result == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_resampler_Jni_resamplerPush(JNIEnv* env,
                                                  jobject thiz,
                                                  jlong ptr,
                                                  jshortArray input,
                                                  jshortArray output) {
  auto* resampler = Resampler(ptr);
  jshort* in = env->GetShortArrayElements(input, nullptr);
  if (in == nullptr) {
    return -1;
  }
  const size_t in_len = static_cast<size_t>(env->GetArrayLength(input));
  const size_t max_len = static_cast<size_t>(env->GetArrayLength(output));

  jshort* out = env->GetShortArrayElements(output, nullptr);
  if (out == nullptr) {
    env->ReleaseShortArrayElements(input, in, JNI_ABORT);
    return -1;
  }

  uint32_t produced = 0;
  const int result =
      webrtc_resampler_push(resampler, in, in_len, out, max_len, &produced);

  env->ReleaseShortArrayElements(input, in, JNI_ABORT);
  env->ReleaseShortArrayElements(output, out, result == 0 ? 0 : JNI_ABORT);

  if (result != 0) {
    return -1;
  }
  return static_cast<jint>(produced);
}

/* =========================================================================
 * webrtc::PushSincResampler
 * ========================================================================= */

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushSincCreate(JNIEnv* env,
                                                   jobject thiz,
                                                   jint source_frames,
                                                   jint destination_frames) {
  auto* resampler = webrtc_resampler_push_sinc_create(
      static_cast<size_t>(source_frames),
      static_cast<size_t>(destination_frames));
  return reinterpret_cast<jlong>(resampler);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushSincDestroy(JNIEnv* env,
                                                    jobject thiz,
                                                    jlong ptr) {
  webrtc_resampler_push_sinc_destroy(PushSinc(ptr));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushSincResampleS16(JNIEnv* env,
                                                        jobject thiz,
                                                        jlong ptr,
                                                        jshortArray source,
                                                        jshortArray destination) {
  jshort* src = env->GetShortArrayElements(source, nullptr);
  if (src == nullptr) {
    return 0;
  }
  jshort* dst = env->GetShortArrayElements(destination, nullptr);
  if (dst == nullptr) {
    env->ReleaseShortArrayElements(source, src, JNI_ABORT);
    return 0;
  }

  const size_t written = webrtc_resampler_push_sinc_resample_s16(
      PushSinc(ptr), src, static_cast<size_t>(env->GetArrayLength(source)), dst,
      static_cast<size_t>(env->GetArrayLength(destination)));

  env->ReleaseShortArrayElements(source, src, JNI_ABORT);
  env->ReleaseShortArrayElements(destination, dst, 0);
  return static_cast<jint>(written);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushSincResampleF32(JNIEnv* env,
                                                        jobject thiz,
                                                        jlong ptr,
                                                        jfloatArray source,
                                                        jfloatArray destination) {
  jfloat* src = env->GetFloatArrayElements(source, nullptr);
  if (src == nullptr) {
    return 0;
  }
  jfloat* dst = env->GetFloatArrayElements(destination, nullptr);
  if (dst == nullptr) {
    env->ReleaseFloatArrayElements(source, src, JNI_ABORT);
    return 0;
  }

  const size_t written = webrtc_resampler_push_sinc_resample_f32(
      PushSinc(ptr), src, static_cast<size_t>(env->GetArrayLength(source)), dst,
      static_cast<size_t>(env->GetArrayLength(destination)));

  env->ReleaseFloatArrayElements(source, src, JNI_ABORT);
  env->ReleaseFloatArrayElements(destination, dst, 0);
  return static_cast<jint>(written);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushSincAlgorithmicDelaySeconds(
    JNIEnv* env, jobject thiz, jint source_rate_hz) {
  return webrtc_resampler_push_sinc_algorithmic_delay_seconds(source_rate_hz);
}

/* =========================================================================
 * webrtc::PushResampler<int16_t>
 * ========================================================================= */

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushS16Create(JNIEnv* env,
                                                  jobject thiz,
                                                  jint src_samples_per_channel,
                                                  jint dst_samples_per_channel,
                                                  jint channels) {
  auto* resampler = webrtc_resampler_push_s16_create(
      static_cast<size_t>(src_samples_per_channel),
      static_cast<size_t>(dst_samples_per_channel),
      static_cast<size_t>(channels));
  return reinterpret_cast<jlong>(resampler);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushS16Destroy(JNIEnv* env,
                                                   jobject thiz,
                                                   jlong ptr) {
  webrtc_resampler_push_s16_destroy(
      reinterpret_cast<webrtc_resampler_push_s16_t*>(ptr));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushS16Resample(JNIEnv* env,
                                                    jobject thiz,
                                                    jlong ptr,
                                                    jshortArray source,
                                                    jshortArray destination) {
  auto* resampler = reinterpret_cast<webrtc_resampler_push_s16_t*>(ptr);
  jshort* src = env->GetShortArrayElements(source, nullptr);
  if (src == nullptr) {
    return;
  }
  jshort* dst = env->GetShortArrayElements(destination, nullptr);
  if (dst == nullptr) {
    env->ReleaseShortArrayElements(source, src, JNI_ABORT);
    return;
  }

  webrtc_resampler_push_s16_resample(resampler, src, dst);

  env->ReleaseShortArrayElements(source, src, JNI_ABORT);
  env->ReleaseShortArrayElements(destination, dst, 0);
}

/* =========================================================================
 * webrtc::PushResampler<float>
 * ========================================================================= */

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushF32Create(JNIEnv* env,
                                                  jobject thiz,
                                                  jint src_samples_per_channel,
                                                  jint dst_samples_per_channel,
                                                  jint channels) {
  auto* resampler = webrtc_resampler_push_f32_create(
      static_cast<size_t>(src_samples_per_channel),
      static_cast<size_t>(dst_samples_per_channel),
      static_cast<size_t>(channels));
  return reinterpret_cast<jlong>(resampler);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushF32Destroy(JNIEnv* env,
                                                   jobject thiz,
                                                   jlong ptr) {
  webrtc_resampler_push_f32_destroy(
      reinterpret_cast<webrtc_resampler_push_f32_t*>(ptr));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_pushF32Resample(JNIEnv* env,
                                                    jobject thiz,
                                                    jlong ptr,
                                                    jfloatArray source,
                                                    jfloatArray destination) {
  auto* resampler = reinterpret_cast<webrtc_resampler_push_f32_t*>(ptr);
  jfloat* src = env->GetFloatArrayElements(source, nullptr);
  if (src == nullptr) {
    return;
  }
  jfloat* dst = env->GetFloatArrayElements(destination, nullptr);
  if (dst == nullptr) {
    env->ReleaseFloatArrayElements(source, src, JNI_ABORT);
    return;
  }

  webrtc_resampler_push_f32_resample(resampler, src, dst);

  env->ReleaseFloatArrayElements(source, src, JNI_ABORT);
  env->ReleaseFloatArrayElements(destination, dst, 0);
}

/* =========================================================================
 * webrtc::SincResampler (pull)
 * ========================================================================= */

namespace {

/* JNIEnv of the resample call currently in flight, for the trampoline. */
JvmSincHandle* ToSincHandle(jlong ptr) {
  return reinterpret_cast<JvmSincHandle*>(ptr);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincCreate(JNIEnv* env,
                                               jobject thiz,
                                               jdouble io_sample_rate_ratio,
                                               jint request_frames,
                                               jobject source) {
  auto* handle = new (std::nothrow) JvmSincHandle();
  if (handle == nullptr) {
    return 0;
  }
  handle->source = new (std::nothrow) JvmSincSource(env, source);
  if (handle->source == nullptr || !handle->source->valid()) {
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    delete handle->source;
    delete handle;
    return 0;
  }
  handle->resampler = webrtc_resampler_sinc_create(
      io_sample_rate_ratio, static_cast<size_t>(request_frames),
      JvmSincReadTrampoline, handle);
  if (handle->resampler == nullptr) {
    handle->source->release(env);
    delete handle->source;
    delete handle;
    return 0;
  }
  return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincDestroy(JNIEnv* env,
                                                jobject thiz,
                                                jlong ptr) {
  auto* handle = ToSincHandle(ptr);
  webrtc_resampler_sinc_destroy(handle->resampler);
  handle->source->release(env);
  delete handle->source;
  delete handle;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincResample(JNIEnv* env,
                                                 jobject thiz,
                                                 jlong ptr,
                                                 jint frames,
                                                 jfloatArray destination) {
  auto* handle = ToSincHandle(ptr);
  jfloat* dst = env->GetFloatArrayElements(destination, nullptr);
  if (dst == nullptr) {
    return;
  }
  // The callback runs on this thread while the call is in flight.
  handle->env = env;
  webrtc_resampler_sinc_resample(handle->resampler,
                                 static_cast<size_t>(frames), dst);
  handle->env = nullptr;
  env->ReleaseFloatArrayElements(destination, dst, 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincChunkSize(JNIEnv* env,
                                                  jobject thiz,
                                                  jlong ptr) {
  return static_cast<jint>(
      webrtc_resampler_sinc_chunk_size(ToSincHandle(ptr)->resampler));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincKernelSize(JNIEnv* env, jobject thiz) {
  return static_cast<jint>(webrtc_resampler_sinc_kernel_size());
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincRequestFrames(JNIEnv* env,
                                                      jobject thiz,
                                                      jlong ptr) {
  return static_cast<jint>(
      webrtc_resampler_sinc_request_frames(ToSincHandle(ptr)->resampler));
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincFlush(JNIEnv* env,
                                              jobject thiz,
                                              jlong ptr) {
  webrtc_resampler_sinc_flush(ToSincHandle(ptr)->resampler);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_enaium_webrtc_resampler_Jni_sincSetRatio(JNIEnv* env,
                                                 jobject thiz,
                                                 jlong ptr,
                                                 jdouble io_sample_rate_ratio) {
  webrtc_resampler_sinc_set_ratio(ToSincHandle(ptr)->resampler,
                                  io_sample_rate_ratio);
}
