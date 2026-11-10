package com.aquabricks.aquarium

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

/**
 * Owns the render thread. The scene draws into a small 640x360 bitmap which is
 * blitted to the TV surface with nearest-neighbour filtering (the pixel-art look)
 * and letterboxed to whatever aspect ratio the display has.
 */
class AquariumView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    val scene = AquariumScene(BUFFER_WIDTH, BUFFER_HEIGHT)

    private val frame = Bitmap.createBitmap(BUFFER_WIDTH, BUFFER_HEIGHT, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frame)
    private val outputPaint = Paint().apply {
        // The scene buffer is authored at 640x360. Filtering the final 3x upscale
        // keeps scanned fish readable instead of showing a field of hard blocks.
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }
    private val destination = RectF()
    private val renderLock = Any()
    private var renderThread: Thread? = null
    private var generation = 0

    @Volatile
    private var activeGeneration = 0
    private var pendingStart = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { feed() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRendering()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        startRendering()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }

    private fun startRendering() {
        synchronized(renderLock) {
            val existing = renderThread
            if (existing != null && existing.isAlive) {
                if (activeGeneration != 0) return
                // A previous loop overran the shutdown join. Let it exit, then
                // hand ownership to a fresh loop from its finally block.
                pendingStart = true
                return
            }
            launchRenderLocked()
        }
    }

    private fun launchRenderLocked() {
        pendingStart = false
        val token = ++generation
        activeGeneration = token
        renderThread = Thread({ renderLoop(token) }, "aquarium-render").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun stopRendering() {
        val thread: Thread?
        synchronized(renderLock) {
            activeGeneration = 0
            pendingStart = false
            thread = renderThread
        }
        thread?.interrupt()
        try {
            thread?.join(1200)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(renderLock) {
            if (renderThread === thread && thread?.isAlive != true) renderThread = null
        }
    }

    private fun renderLoop(token: Int) {
        var last = System.nanoTime()
        try {
            while (token == activeGeneration) {
                val started = System.nanoTime()
                var dt = (started - last) / 1_000_000_000f
                last = started
                if (dt > 0.12f) dt = 0.12f
                if (dt < 0f) dt = 0f

                if (!holder.surface.isValid) {
                    sleepQuietly(60)
                    continue
                }

                var canvas: Canvas? = null
                try {
                    synchronized(scene) {
                        scene.update(dt)
                        scene.draw(frameCanvas)
                    }
                    canvas = lockSurface()
                    if (canvas != null) {
                        // A 16:9 scene on a taller phone gets side bars; use deep water
                        // instead of black so the frame stays part of the tank.
                        canvas.drawColor(Color.rgb(11, 33, 43))
                        val screenWidth = canvas.width.toFloat()
                        val screenHeight = canvas.height.toFloat()
                        val scale = min(screenWidth / BUFFER_WIDTH, screenHeight / BUFFER_HEIGHT)
                        val drawWidth = BUFFER_WIDTH * scale
                        val drawHeight = BUFFER_HEIGHT * scale
                        destination.set(
                            (screenWidth - drawWidth) / 2f,
                            (screenHeight - drawHeight) / 2f,
                            (screenWidth + drawWidth) / 2f,
                            (screenHeight + drawHeight) / 2f
                        )
                        canvas.drawBitmap(frame, null, destination, outputPaint)
                    }
                } catch (_: Exception) {
                    // The surface can go away between isValid and lockCanvas.
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                        }
                    }
                }

                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                val remaining = FRAME_MS - elapsedMs
                if (remaining > 0) sleepQuietly(remaining)
            }
        } finally {
            synchronized(renderLock) {
                if (renderThread === Thread.currentThread()) renderThread = null
                if (pendingStart && holder.surface.isValid) {
                    pendingStart = false
                    launchRenderLocked()
                }
            }
        }
    }

    /**
     * The scene is a software bitmap; only the final upscale needs to happen on
     * the surface. A hardware canvas lets the GPU do that scale instead of the CPU,
     * which matters on the Chromecast HD. Fall back to a software canvas if the
     * device refuses a hardware lock.
     */
    private fun lockSurface(): Canvas? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                holder.lockHardwareCanvas()
            } else {
                holder.lockCanvas()
            }
        } catch (_: Exception) {
            try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun addFish(meta: FishMeta, bitmap: Bitmap, announce: Boolean) {
        synchronized(scene) {
            scene.addFish(meta, bitmap, announce)
        }
    }

    fun removeFish(id: String) {
        synchronized(scene) { scene.removeFish(id) }
    }

    fun setTheme(theme: AquariumTheme, announce: Boolean = false) {
        synchronized(scene) { scene.setTheme(theme, announce) }
    }

    fun cycleTheme(delta: Int): AquariumTheme =
        synchronized(scene) { scene.cycleTheme(delta) }

    fun feed() {
        synchronized(scene) { scene.feed() }
    }

    fun setLoaded() {
        synchronized(scene) { scene.setLoaded(true) }
    }

    fun setServerInfo(ip: String, port: Int, running: Boolean) {
        synchronized(scene) { scene.setServerInfo(ip, port, running) }
    }

    companion object {
        const val BUFFER_WIDTH = 640
        const val BUFFER_HEIGHT = 360
        private const val FRAME_MS = 33L
    }
}
