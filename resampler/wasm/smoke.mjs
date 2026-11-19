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
 * Smoke test for the wasm module and its glue, run by the runWasmSmoke Gradle
 * task (and therefore in CI).
 *
 * Kotlin/Wasm has no unit test runner for a library whose JS side lives in an
 * npm package, so the same contracts the shared test suite checks on the JVM
 * and on the native targets are exercised here through the glue: the module
 * loads, the bindings answer, the block arithmetic of the multi-rate and the
 * push sinc resamplers holds, and a tone survives resampling.
 *
 * Usage: node smoke.mjs <dir with the staged npm package>
 */

import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const packageDir = process.argv[2];
if (!packageDir) {
    console.error("usage: node smoke.mjs <dir with the staged npm package>");
    process.exit(2);
}

const glueUrl = new URL(join(packageDir, "webrtc_resampler_kmp_glue.js"), `file://${process.cwd()}/`);
const { initWebrtcResamplerKmp } = await import(glueUrl.href);
await initWebrtcResamplerKmp();

const M = globalThis;

let checks = 0;
let failures = 0;

function check(condition, what) {
    checks += 1;
    if (!condition) {
        failures += 1;
        console.log(`  FAIL: ${what}`);
    }
}

const AMPLITUDE = 8192;

function sine(rate, seconds, frequency, amplitude = AMPLITUDE) {
    const frames = Math.round(rate * seconds);
    const out = new Int16Array(frames);
    for (let i = 0; i < frames; i++) {
        out[i] = Math.max(-32768, Math.min(32767, Math.round(amplitude * Math.sin((2 * Math.PI * frequency * i) / rate))));
    }
    return out;
}

/** Coherent DFT amplitude of `frequency` over `count` samples from `start`. */
function toneAmplitude(samples, start, count, rate, frequency) {
    let re = 0;
    let im = 0;
    for (let i = 0; i < count; i++) {
        const w = (2 * Math.PI * frequency * i) / rate;
        const x = samples[start + i];
        re += x * Math.cos(w);
        im -= x * Math.sin(w);
    }
    return (2 * Math.sqrt(re * re + im * im)) / count;
}

function ratioToDb(numerator, denominator) {
    return 20 * Math.log10(Math.max(numerator, 1e-12) / Math.max(denominator, 1e-12));
}

/** Runs one call with the sample buffer copied into the module heap. */
function pushShort(handle, input, outputLength) {
    const inPtr = M.webrtcResamplerKmpMalloc(input.length * 2);
    const outPtr = M.webrtcResamplerKmpMalloc(outputLength * 2);
    try {
        M.webrtcResamplerKmpWriteS16(inPtr, input);
        const produced = M.webrtc_resampler_push(handle, inPtr, input.length, outPtr, outputLength);
        return { produced, output: Array.from(M.webrtcResamplerKmpReadS16(outPtr, Math.max(produced, 0))) };
    } finally {
        M.webrtcResamplerKmpFree(inPtr);
        M.webrtcResamplerKmpFree(outPtr);
    }
}

function pushSincShort(handle, input, sourceFrames, outputLength) {
    const inPtr = M.webrtcResamplerKmpMalloc(sourceFrames * 2);
    const outPtr = M.webrtcResamplerKmpMalloc(outputLength * 2);
    try {
        M.webrtcResamplerKmpWriteS16(inPtr, input);
        const written = M.webrtc_resampler_push_sinc_resample_s16(handle, inPtr, sourceFrames, outPtr, outputLength);
        return Array.from(M.webrtcResamplerKmpReadS16(outPtr, written));
    } finally {
        M.webrtcResamplerKmpFree(inPtr);
        M.webrtcResamplerKmpFree(outPtr);
    }
}

// -------------------------------------------------------------------------
// Version
// -------------------------------------------------------------------------
const version = M.webrtc_resampler_version();
check(typeof version === "string" && version.length > 0, "version() returns a string");
console.log(`webrtc-resampler ${version}`);

// -------------------------------------------------------------------------
// Multi-rate resampler: 1:1 passthrough is bit exact
// -------------------------------------------------------------------------
{
    const input = sine(48000, 0.1, 1000);
    const resampler = M.webrtc_resampler_create_reset(48000, 48000, 1);
    check(resampler !== 0, "create_reset(48000, 48000, 1)");
    const { produced, output } = pushShort(resampler, input, input.length);
    check(produced === input.length, `1:1 produced ${input.length} samples, got ${produced}`);
    check(output.every((v, i) => v === input[i]), "1:1 output equals the input");
    M.webrtc_resampler_destroy(resampler);
}

// -------------------------------------------------------------------------
// Multi-rate resampler: 10 ms blocks follow the ratio
// -------------------------------------------------------------------------
{
    const cases = [
        [48000, 16000, 480, 160],
        [16000, 48000, 160, 480],
        [8000, 48000, 80, 480],
        [48000, 8000, 480, 80],
    ];
    for (const [inRate, outRate, blockIn, blockOut] of cases) {
        const input = sine(inRate, 0.1, 400);
        const resampler = M.webrtc_resampler_create_reset(inRate, outRate, 1);
        check(resampler !== 0, `create_reset(${inRate}, ${outRate}, 1)`);
        const block = input.slice(0, blockIn);
        const { produced } = pushShort(resampler, block, blockOut);
        check(produced === blockOut, `${inRate} -> ${outRate}: expected ${blockOut} samples, got ${produced}`);
        M.webrtc_resampler_destroy(resampler);
    }
}

// -------------------------------------------------------------------------
// Multi-rate resampler: unsupported rate pairs are rejected
// -------------------------------------------------------------------------
{
    check(M.webrtc_resampler_create_reset(44100, 48000, 1) === 0, "44.1 kHz -> 48 kHz is rejected");
    check(M.webrtc_resampler_create_reset(48000, 16000, 3) === 0, "3 channels are rejected");
    const resampler = M.webrtc_resampler_create();
    check(resampler !== 0, "create()");
    check(M.webrtc_resampler_reset(resampler, 22050, 16000, 1) !== 0, "22.05 kHz is rejected by reset()");
    check(M.webrtc_resampler_reset(resampler, 48000, 16000, 1) === 0, "48 kHz -> 16 kHz is accepted by reset()");
    M.webrtc_resampler_destroy(resampler);
}

// -------------------------------------------------------------------------
// Push sinc: 48 kHz -> 16 kHz keeps a 1 kHz tone at its level
// -------------------------------------------------------------------------
{
    const input = sine(48000, 1.0, 1000);
    const resampler = M.webrtc_resampler_push_sinc_create(480, 160);
    check(resampler !== 0, "push_sinc_create(480, 160)");

    const output = [];
    for (let pos = 0; pos + 480 <= input.length; pos += 480) {
        const block = pushSincShort(resampler, input.slice(pos, pos + 480), 480, 160);
        check(block.length === 160, `push sinc produced ${block.length} samples, expected 160`);
        output.push(...block);
    }
    check(output.length === (input.length / 480) * 160, "push sinc output length matches the ratio");

    const start = 800;
    const count = 8000;
    const level = ratioToDb(toneAmplitude(output, start, count, 16000, 1000), AMPLITUDE);
    check(Math.abs(level) < 1.0, `1 kHz level drifted by ${level.toFixed(2)} dB`);

    M.webrtc_resampler_push_sinc_destroy(resampler);
}

// -------------------------------------------------------------------------
// Push sinc: floating point blocks at 44.1 kHz -> 48 kHz
// -------------------------------------------------------------------------
{
    const resampler = M.webrtc_resampler_push_sinc_create(441, 480);
    check(resampler !== 0, "push_sinc_create(441, 480)");
    const input = new Float32Array(441);
    for (let i = 0; i < input.length; i++) {
        input[i] = 0.5 * Math.sin((2 * Math.PI * 1000 * i) / 44100);
    }
    const inPtr = M.webrtcResamplerKmpMalloc(441 * 4);
    const outPtr = M.webrtcResamplerKmpMalloc(480 * 4);
    try {
        M.webrtcResamplerKmpWriteF32(inPtr, input);
        const written = M.webrtc_resampler_push_sinc_resample_f32(resampler, inPtr, 441, outPtr, 480);
        check(written === 480, `44.1 kHz -> 48 kHz produced ${written} float samples, expected 480`);
    } finally {
        M.webrtcResamplerKmpFree(inPtr);
        M.webrtcResamplerKmpFree(outPtr);
        M.webrtc_resampler_push_sinc_destroy(resampler);
    }
}

// -------------------------------------------------------------------------
// PushResampler<int16_t>: stereo channels stay separate
// -------------------------------------------------------------------------
{
    const inRate = 48000;
    const outRate = 16000;
    const blockIn = 480;
    const blockOut = 160;
    const frames = 24000;
    const input = new Int16Array(frames * 2);
    for (let i = 0; i < frames; i++) {
        input[i * 2] = Math.round(AMPLITUDE * Math.sin((2 * Math.PI * 1000 * i) / inRate));
        input[i * 2 + 1] = Math.round(AMPLITUDE * Math.sin((2 * Math.PI * 3000 * i) / inRate));
    }

    const resampler = M.webrtc_resampler_push_s16_create(blockIn, blockOut, 2);
    check(resampler !== 0, "push_s16_create(480, 160, 2)");

    const outFrames = (frames / blockIn) * blockOut;
    const output = new Int16Array(outFrames * 2);
    const inPtr = M.webrtcResamplerKmpMalloc(blockIn * 2 * 2);
    const outPtr = M.webrtcResamplerKmpMalloc(blockOut * 2 * 2);
    try {
        let written = 0;
        for (let frame = 0; frame + blockIn <= frames; frame += blockIn) {
            M.webrtcResamplerKmpWriteS16(inPtr, input.subarray(frame * 2, (frame + blockIn) * 2));
            M.webrtc_resampler_push_s16_resample(resampler, inPtr, outPtr);
            const block = M.webrtcResamplerKmpReadS16(outPtr, blockOut * 2);
            for (let i = 0; i < blockOut * 2; i++) {
                output[written * 2 + i] = block[i];
            }
            written += blockOut;
        }
        check(written === outFrames, `stereo produced ${written} frames, expected ${outFrames}`);

        const left = [];
        const right = [];
        for (let i = 0; i < written; i++) {
            left.push(output[i * 2]);
            right.push(output[i * 2 + 1]);
        }
        const start = 800;
        const count = 4000;
        const leftLevel = ratioToDb(toneAmplitude(left, start, count, outRate, 1000), AMPLITUDE);
        const rightLevel = ratioToDb(toneAmplitude(right, start, count, outRate, 3000), AMPLITUDE);
        check(Math.abs(leftLevel) < 1.0, `left level drifted by ${leftLevel.toFixed(2)} dB`);
        check(Math.abs(rightLevel) < 1.0, `right level drifted by ${rightLevel.toFixed(2)} dB`);
        const bleed = ratioToDb(toneAmplitude(left, start, count, outRate, 3000), toneAmplitude(left, start, count, outRate, 1000));
        check(bleed < -60.0, `right channel bled into left by ${bleed.toFixed(1)} dB`);
    } finally {
        M.webrtcResamplerKmpFree(inPtr);
        M.webrtcResamplerKmpFree(outPtr);
        M.webrtc_resampler_push_s16_destroy(resampler);
    }
}

console.log(`\n${checks} checks, ${failures} failures`);
process.exit(failures === 0 ? 0 : 1);
