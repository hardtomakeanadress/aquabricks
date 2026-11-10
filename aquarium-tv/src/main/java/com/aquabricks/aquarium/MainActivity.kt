package com.aquabricks.aquarium

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var aquariumView: AquariumView
    private lateinit var server: FishServer
    private lateinit var repository: FishRepository
    private val prefs by lazy { getSharedPreferences("aquabricks", Context.MODE_PRIVATE) }
    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fish-loader").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        repository = FishRepository(this)
        aquariumView = AquariumView(this)
        setContentView(aquariumView)
        // Must happen after setContentView: PhoneWindow.getInsetsController()
        // dereferences the DecorView, which does not exist before content is set.
        goImmersive()
        prefs.getString("theme", null)?.let { stored ->
            runCatching { AquariumTheme.valueOf(stored) }.getOrNull()?.let {
                aquariumView.setTheme(it, announce = false)
            }
        }

        server = FishServer(
            repository = repository,
            tankName = FishServer.TANK_DISPLAY_NAME,
            onAdded = { meta, bitmap ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) aquariumView.addFish(meta, bitmap, true)
                }
            },
            onDeleted = { id ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) aquariumView.removeFish(id)
                }
            }
        )
        val started = server.start()
        aquariumView.setServerInfo(server.localIp, server.httpPort, started)

        loadRememberedFish()
    }

    private fun loadRememberedFish() {
        loader.execute {
            val entries = repository.entries()
            for (meta in entries) {
                val bitmap: Bitmap = try {
                    BitmapFactory.decodeFile(repository.fileFor(meta).absolutePath)
                } catch (_: Exception) {
                    null
                } ?: continue
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && repository.contains(meta.id)) {
                        aquariumView.addFish(meta, bitmap, false)
                    }
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) aquariumView.setLoaded()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_F,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    aquariumView.feed()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    changeTheme(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_T -> {
                    changeTheme(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun changeTheme(delta: Int) {
        val theme = aquariumView.cycleTheme(delta)
        prefs.edit().putString("theme", theme.name).apply()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        server.stop()
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                return
            } catch (_: Exception) {
                // Some TV firmware exposes the controller before the decor view
                // is ready; fall through to the legacy flags.
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}
