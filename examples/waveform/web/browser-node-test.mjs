// Node.js harness for the wasm browser app.
//
// SDL3's Emscripten driver expects browser globals even in dummy-driver mode,
// so stub the minimal set, force the dummy video/audio drivers (Emscripten has
// no environment to read the hints from) and stop the run after a fixed number
// of frames. This exercises the whole pipeline: Kotlin/Wasm -> resampler wasm
// module -> audio-io wasm glue -> Emscripten SDL3/ImGui -> frame loop.

globalThis.window = {
    matchMedia: () => ({ addEventListener: () => {} }),
    addEventListener: () => {},
    requestAnimationFrame: (cb) => setTimeout(cb, 16),
    screenX: 0,
    screenY: 0,
    innerWidth: 1280,
    innerHeight: 800,
    devicePixelRatio: 1,
    location: { href: 'http://localhost/' },
};
globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);
globalThis.screen = { width: 1280, height: 800, availWidth: 1280, availHeight: 800 };
globalThis.document = {
    createElement: (tag) => ({
        tagName: tag.toUpperCase(),
        style: {},
        width: 1280,
        height: 800,
        getContext: () => null,
        addEventListener: () => {},
        setPointerCapture: () => {},
    }),
    getElementById: () => null,
    querySelector: () => null,
    addEventListener: () => {},
    body: { appendChild: () => {} },
    head: { appendChild: () => {} },
    createEvent: () => ({ initEvent: () => {} }),
    visibilityState: 'visible',
};
Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: {
        userAgent: 'node',
        platform: 'node',
        hardwareConcurrency: 4,
        language: 'en',
        languages: ['en'],
        mediaDevices: { enumerateDevices: async () => [] },
    },
});
globalThis.performance = { now: () => Date.now() };
globalThis.CanvasRenderingContext2D = function () {};

// AudioContext stub so the Web Audio backend can open a stream; it never
// delivers callbacks, so the pipeline counts what it cannot play as dropped and
// keeps rendering, which is what the example does on a machine with no device.
globalThis.AudioContext = class {
    constructor() { this.sampleRate = 48000; this.state = 'running'; }
    createBuffer() { return { getChannelData: () => new Float32Array(0) }; }
    createBufferSource() { return { connect: () => {}, start: () => {}, buffer: null }; }
    createScriptProcessor() { return { connect: () => {}, onaudioprocess: null }; }
    createAnalyser() { return { connect: () => {} }; }
    createGain() { return { connect: () => {}, gain: { value: 1 } }; }
    destination = {};
    resume() { return Promise.resolve(); }
    close() { return Promise.resolve(); }
};

const { initSdlKmp } = await import('@enaium/sdl-kmp');
await initSdlKmp();

const { initImguiKmp } = await import('@enaium/imgui-kmp');
await initImguiKmp();

const { initWebrtcResamplerKmp } = await import('@enaium/webrtc-resampler-kmp');
await initWebrtcResamplerKmp();

await import('@enaium/audio-io-kmp');

sdl_kmp_SetHint('SDL_VIDEO_DRIVER', 'dummy');
sdl_kmp_SetHint('SDL_AUDIO_DRIVER', 'dummy');

globalThis.webrtcResamplerKmpFrames = 120;

// Import the Kotlin/Wasm module; its runtime auto-invokes main().
await import('./webrtc-resampler-kmp-examples-waveform.mjs');
