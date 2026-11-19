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

package cn.enaium.webrtc.resampler.examples.waveform

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiContext
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlGpuBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotContext
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLGPU
import cn.enaium.sdl.SDLGPUDevice
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags

/**
 * Boots an SDL3 window with the GPU API, the published imgui-kmp SDL backends
 * and an ImPlot context, then runs the frame loop calling [draw].
 *
 * The frame loop itself is only one driver over [Session]: the platforms with
 * threads run it in [run], and the wasmJs entry drives [Session.frame] from
 * `requestAnimationFrame`, because a browser cannot paint while a blocking loop
 * runs.
 */
object ImGuiSdlApp {

    /** Font size the layout is designed around. */
    private const val BASE_FONT_SIZE = 13f

    /**
     * Runs until the window is closed or [frames] frames were drawn; returns
     * `false` when no window could be opened at all.
     *
     * `--frames` is passed through from the entry points to bound the run.
     */
    fun run(title: String, frames: Int = Int.MAX_VALUE, draw: (frame: Int) -> Unit): Boolean {
        val session = open(title) ?: return false
        session.use {
            var frame = 0
            while (frame < frames && session.frame(frame, draw)) {
                frame++
            }
        }
        return true
    }

    /**
     * Opens the window, the renderer and the ImGui/ImPlot contexts without
     * running a loop, or returns `null` when no window could be opened.
     */
    internal fun open(title: String): Session? {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            // No silent fallback to SDL's dummy driver: rendering into an
            // invisible window looks exactly like a broken app. Headless runs
            // set SDL_VIDEO_DRIVER=dummy, which SDL picks up by itself.
            println("SDL_Init failed: ${SDL.error()}$videoInitHint")
            return null
        }
        val version = SDL.version()
        report("SDL ${version.major}.${version.minor}.${version.micro} (${SDL.revision()})")
        report("video driver: ${SDL.getCurrentVideoDriver()}")

        val window = try {
            SDL.createWindow(
                title = title,
                width = 1280,
                height = 800,
                flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
            )
        } catch (t: Throwable) {
            SDL.quit()
            throw t
        }

        // SDL_GPU is what the example renders with: it goes through Vulkan,
        // Metal or D3D12 rather than the 2D renderer's GLES path. Where it is
        // not available - a headless CI run on SDL's dummy driver, a machine
        // with no usable GPU driver, or the Emscripten build - the reason is
        // logged and the 2D renderer takes over, so the example still runs.
        val device = if (SDLGPU.isSupported) SDLGPU.createDevice() else null
        val gpuDevice = when {
            device == null -> {
                report("SDL_GPU is not supported on this platform; using the SDL renderer backend")
                null
            }

            device.claimWindow(window) -> {
                report("renderer: SDL_GPU [${SDLGPU.drivers.joinToString()}]")
                device
            }

            else -> {
                report("SDL_GPU cannot drive this window (${SDL.error()}); using the SDL renderer backend")
                device.close()
                null
            }
        }

        val context = ImGui.createContext()
        val platform = ImGuiSdlBackend(window)
        platform.init()
        report("window ${window.sizeInPixels.x}x${window.sizeInPixels.y} px")
        val frameBackend = try {
            if (gpuDevice != null) GpuFrameBackend(gpuDevice, window, platform) else RendererFrameBackend(window)
        } catch (t: Throwable) {
            ImGui.destroyContext(context)
            gpuDevice?.releaseDrawable(window)
            gpuDevice?.close()
            window.close()
            SDL.quit()
            throw t
        }

        try {
            // Bake the font at the display's pixel density, so a Retina screen
            // gets crisp glyphs.
            val fonts = ImGui.getIO().fonts
            val density = maxOf(platform.framebufferScale.x, platform.framebufferScale.y, 1f)
            fonts.addFontDefault(
                ImFontConfig(
                    sizePixels = BASE_FONT_SIZE * density,
                    rasterizerDensity = density,
                ),
            )
            check(fonts.build()) { "font atlas build failed" }
            val texData = fonts.getTexDataAsRGBA32()
            fonts.setTexID(frameBackend.uploadFontTexture(texData.pixels, texData.width, texData.height))

            // ImPlot keeps its own context, bound to the imgui one.
            val plotContext = ImPlot.createContext()
            ImPlot.setImGuiContext(ImGui.getCurrentContext() ?: error("no imgui context"))

            return Session(window, gpuDevice, platform, frameBackend, context, plotContext)
        } catch (t: Throwable) {
            frameBackend.close()
            ImGui.destroyContext(context)
            gpuDevice?.releaseDrawable(window)
            gpuDevice?.close()
            window.close()
            SDL.quit()
            throw t
        }
    }

    /** An opened window with everything one frame needs. */
    internal class Session internal constructor(
        private val window: SDLWindow,
        private val gpuDevice: SDLGPUDevice?,
        private val platform: ImGuiSdlBackend,
        private val frameBackend: FrameBackend,
        private val imguiContext: ImGuiContext,
        private val plotContext: ImPlotContext,
    ) : AutoCloseable {

        private var closed = false

        private var lastReport = SDL.getTicks()

        private var framesAtReport = 0

        private var drawMillis = 0.0

        private var renderMillis = 0.0

        private var buildMillis = 0.0

        /**
         * Draws one frame with [draw] and presents it; returns `false` when the
         * window was closed (a quit event or a close request).
         */
        fun frame(frame: Int, draw: (frame: Int) -> Unit): Boolean {
            var running = true
            while (true) {
                val event = SDL.pollEvent() ?: break
                when (event) {
                    is SDLEvent.Quit -> running = false
                    is SDLEvent.Window ->
                        if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false

                    else -> platform.processEvent(event)
                }
            }
            if (!running) return false

            val t0 = SDL.getTicks()
            platform.newFrame()
            draw(frame)
            val t1 = SDL.getTicks()
            ImGui.render()
            val t2 = SDL.getTicks()
            frameBackend.render()
            val t3 = SDL.getTicks()
            drawMillis += (t1 - t0).toDouble()
            buildMillis += (t2 - t1).toDouble()
            renderMillis += (t3 - t2).toDouble()

            // The frame rate is what tells whether the render loop is still
            // doing anything but drawing.
            val now = SDL.getTicks()
            if (now - lastReport >= FRAME_REPORT_MILLIS) {
                val elapsed = (now - lastReport).toDouble() / 1000.0
                val count = (frame + 1 - framesAtReport).toDouble()
                report(
                    "render: ${fixed(count / elapsed, 1)} fps, draw ${fixed(drawMillis / count, 1)} ms, " +
                        "imgui ${fixed(buildMillis / count, 1)} ms, present ${fixed(renderMillis / count, 1)} ms",
                )
                framesAtReport = frame + 1
                drawMillis = 0.0
                renderMillis = 0.0
                buildMillis = 0.0
                lastReport = now
            }
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            frameBackend.close()
            ImPlot.destroyContext(plotContext)
            ImGui.destroyContext(imguiContext)
            gpuDevice?.releaseDrawable(window)
            gpuDevice?.close()
            window.close()
            SDL.quit()
        }
    }

    /** Presents ImGui frames with one of SDL's rendering paths. */

    /** Milliseconds between two frame rate reports. */
    private const val FRAME_REPORT_MILLIS = 2000u

    internal interface FrameBackend {

        /** Uploads the baked font atlas and returns the texture to draw it with. */
        fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID

        /** Draws and presents the frame the render loop just produced. */
        fun render()

        /** Releases everything the backend owns. */
        fun close()
    }

    /**
     * SDL_GPU: one command buffer per frame, drawing into the window's
     * swapchain texture with the imgui-kmp SDL GPU backend.
     */
    private class GpuFrameBackend(
        private val device: SDLGPUDevice,
        private val window: SDLWindow,
        private val platform: ImGuiSdlBackend,
    ) : FrameBackend {

        private val backend = ImGuiSdlGpuBackend(device, window)

        override fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID =
            backend.uploadFontTexture(pixels, width, height)

        override fun render() {
            val commandBuffer = device.beginCommandBuffer()
            if (commandBuffer == null) {
                println("SDL_AcquireGPUCommandBuffer failed: ${SDL.error()}")
                return
            }
            val swapchain = device.acquireSwapchainTexture(commandBuffer, window)
            val target = swapchain?.texture
            if (target != null) {
                val size = platform.sizeInPixels
                backend.renderFrame(commandBuffer, target, size.x.toInt(), size.y.toInt())
            }
            // A missing swapchain texture (hidden window, reconfiguring
            // swapchain) still needs the command buffer to be submitted.
            device.submit(commandBuffer)
        }

        override fun close() {
            backend.close()
        }
    }

    /** SDL's 2D renderer, used where SDL_GPU is not available. */
    private class RendererFrameBackend(window: SDLWindow) : FrameBackend {

        private val renderer = SDL.createRenderer(window)

        private val backend = ImGuiSdlRendererBackend(renderer)

        override fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID =
            backend.uploadFontTexture(pixels, width, height)

        override fun render() {
            renderer.drawColor = SDLColor(18, 18, 24)
            renderer.clear()
            backend.renderDrawData(ImGui.getDrawData())
            renderer.present()
        }

        override fun close() {
            backend.close()
            renderer.close()
        }
    }
}
