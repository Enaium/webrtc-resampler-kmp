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
 * JS glue for the wasmJs target. The resampler is compiled by Emscripten into
 * webrtc_resampler_wasm.js/.wasm (see ../../build.gradle.kts); this file
 * instantiates that module and publishes every function the Kotlin/Wasm actuals
 * declare as an external, as a global.
 *
 * Marshaling conventions (see jni/c_api/webrtc_resampler_c.h):
 *   - handles are numbers (pointers into the module's heap);
 *   - sample arrays are copied into a heap buffer for the call and freed after,
 *     with the typed array helpers below doing the element copies;
 *   - webrtc_resampler_push writes its produced-sample count through a
 *     size_t*, which the wrapper keeps in a scratch slot and returns as the
 *     call's result (-1 on failure, like the C function).
 *
 * The host page has to await initWebrtcResamplerKmp() before the Kotlin/Wasm
 * module runs; every function below appears at that point.
 */

let modulePromise = null;

/**
 * Loads and initializes the Emscripten resampler module, then publishes the
 * bridge functions as globals.
 *
 * @returns {Promise<object>} the Emscripten module instance
 */
export function initWebrtcResamplerKmp() {
    if (modulePromise === null) {
        modulePromise = (async () => {
            const mod = await import('./webrtc_resampler_wasm.js');
            const load = typeof mod.default === 'function' ? mod.default : mod;
            const Module = await load();
            const C = Module.cwrap;
            const malloc = Module._malloc;
            const free = Module._free;

            /* Heap access, used to copy sample buffers in and out. The views
             * are fetched per call because a growing memory replaces them. */
            globalThis.webrtcResamplerKmpMalloc = (size) => malloc(size);
            globalThis.webrtcResamplerKmpFree = (ptr) => free(ptr);
            globalThis.webrtcResamplerKmpWriteS16 = (ptr, data) => {
                Module.HEAP16.set(data, ptr >> 1);
            };
            globalThis.webrtcResamplerKmpWriteF32 = (ptr, data) => {
                Module.HEAPF32.set(data, ptr >> 2);
            };
            globalThis.webrtcResamplerKmpReadS16 = (ptr, length) =>
                Module.HEAP16.subarray(ptr >> 1, (ptr >> 1) + length);
            globalThis.webrtcResamplerKmpReadF32 = (ptr, length) =>
                Module.HEAPF32.subarray(ptr >> 2, (ptr >> 2) + length);

            /* Scratch slot for the size_t* out-parameter of webrtc_resampler_push. */
            const pushOutLen = malloc(4);
            const push = C('webrtc_resampler_push', 'number',
                ['number', 'number', 'number', 'number', 'number', 'number']);

            globalThis.webrtc_resampler_version =
                C('webrtc_resampler_version', 'string', []);
            globalThis.webrtc_resampler_create =
                C('webrtc_resampler_create', 'number', []);
            globalThis.webrtc_resampler_create_reset =
                C('webrtc_resampler_create_reset', 'number', ['number', 'number', 'number']);
            globalThis.webrtc_resampler_destroy =
                C('webrtc_resampler_destroy', null, ['number']);
            globalThis.webrtc_resampler_reset =
                C('webrtc_resampler_reset', 'number', ['number', 'number', 'number', 'number']);
            globalThis.webrtc_resampler_reset_if_needed =
                C('webrtc_resampler_reset_if_needed', 'number', ['number', 'number', 'number', 'number']);
            globalThis.webrtc_resampler_push =
                (handle, inPtr, inLen, outPtr, maxLen) => {
                    const status = push(handle, inPtr, inLen, outPtr, maxLen, pushOutLen);
                    return status === 0 ? Module.HEAPU32[pushOutLen >> 2] : -1;
                };

            globalThis.webrtc_resampler_push_sinc_create =
                C('webrtc_resampler_push_sinc_create', 'number', ['number', 'number']);
            globalThis.webrtc_resampler_push_sinc_destroy =
                C('webrtc_resampler_push_sinc_destroy', null, ['number']);
            globalThis.webrtc_resampler_push_sinc_resample_s16 =
                C('webrtc_resampler_push_sinc_resample_s16', 'number',
                    ['number', 'number', 'number', 'number', 'number']);
            globalThis.webrtc_resampler_push_sinc_resample_f32 =
                C('webrtc_resampler_push_sinc_resample_f32', 'number',
                    ['number', 'number', 'number', 'number', 'number']);
            globalThis.webrtc_resampler_push_sinc_algorithmic_delay_seconds =
                C('webrtc_resampler_push_sinc_algorithmic_delay_seconds', 'number', ['number']);

            globalThis.webrtc_resampler_push_s16_create =
                C('webrtc_resampler_push_s16_create', 'number', ['number', 'number', 'number']);
            globalThis.webrtc_resampler_push_s16_destroy =
                C('webrtc_resampler_push_s16_destroy', null, ['number']);
            globalThis.webrtc_resampler_push_s16_resample =
                C('webrtc_resampler_push_s16_resample', null, ['number', 'number', 'number']);

            globalThis.webrtc_resampler_push_f32_create =
                C('webrtc_resampler_push_f32_create', 'number', ['number', 'number', 'number']);
            globalThis.webrtc_resampler_push_f32_destroy =
                C('webrtc_resampler_push_f32_destroy', null, ['number']);
            globalThis.webrtc_resampler_push_f32_resample =
                C('webrtc_resampler_push_f32_resample', null, ['number', 'number', 'number']);

            globalThis.webrtc_resampler_sinc_kernel_size =
                C('webrtc_resampler_sinc_kernel_size', 'number', []);

            globalThis.webrtcResamplerKmpInitialized = true;
            return Module;
        })();
    }
    return modulePromise;
}
