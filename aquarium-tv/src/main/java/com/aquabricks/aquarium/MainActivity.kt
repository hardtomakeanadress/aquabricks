package com.aquabricks.aquarium

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
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
    private var ambience: MediaPlayer? = null
    private var musicEnabled = true
    private var sharkEnabled = true
    private val prefs by lazy { getSharedPreferences("aquabricks", Context.MODE_PRIVATE) }
    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fish-loader").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        repository = FishRepository(this)
        aquariumView = AquariumView(this)
        aquariumView.onThemeCycle = { delta -> changeTheme(delta) }
        aquariumView.onMusicToggle = { setMusicEnabled(!musicEnabled) }
        aquariumView.onFishEaten = { meta ->
            // The render thread must not block on the index rewrite. Removing the
            // fish here also keeps it gone after the next restart and in the phone's
            // "manage fish" list, which reads the same repository.
            runCatching { loader.execute { repository.delete(meta.id) } }
        }
        setContentView(aquariumView)
        // Must happen after setContentView: PhoneWindow.getInsetsController()
        // dereferences the DecorView, which does not exist before content is set.
        goImmersive()
        prefs.getString("theme", null)?.let { stored ->
            runCatching { AquariumTheme.valueOf(stored) }.getOrNull()?.let {
                aquariumView.setTheme(it, announce = false)
            }
        }
        musicEnabled = prefs.getBoolean("music", true)
        aquariumView.setMusicEnabled(musicEnabled, announce = false)
        sharkEnabled = prefs.getBoolean("shark", true)
        aquariumView.setSharkEnabled(sharkEnabled, announce = false)

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
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
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
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        setMusicEnabled(true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        setMusicEnabled(false)
                        return true
                    }
                    KeyEvent.KEYCODE_M,
                    KeyEvent.KEYCODE_MUTE,
                    KeyEvent.KEYCODE_VOLUME_MUTE -> {
                        setMusicEnabled(!musicEnabled)
                        return true
                    }
                    KeyEvent.KEYCODE_S -> {
                        setSharkEnabled(!sharkEnabled)
                        return true
                    }
                }
            } else if (
                event.repeatCount == LONG_PRESS_REPEAT &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                // Holding OK is the remote-friendly shark switch; a short press
                // still feeds as before.
                setSharkEnabled(!sharkEnabled)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setSharkEnabled(enabled: Boolean) {
        sharkEnabled = enabled
        prefs.edit().putBoolean("shark", sharkEnabled).apply()
        aquariumView.setSharkEnabled(sharkEnabled, announce = true)
    }

    private fun changeTheme(delta: Int) {
        val theme = aquariumView.cycleTheme(delta)
        prefs.edit().putString("theme", theme.name).apply()
    }

    private fun setMusicEnabled(enabled: Boolean) {
        musicEnabled = enabled
        prefs.edit().putBoolean("music", musicEnabled).apply()
        if (musicEnabled) {
            startOrResumeAmbience()
        } else {
            ambience?.pause()
        }
        // Also flash the icon when the requested state was already active, so the
        // remote control gives immediate feedback.
        aquariumView.setMusicEnabled(musicEnabled, announce = true)
    }

    private fun startOrResumeAmbience() {
        if (!musicEnabled) return
        val existing = ambience
        if (existing != null) {
            try {
                if (!existing.isPlaying) existing.start()
            } catch (error: Exception) {
                Log.w("Aquarium", "Could not resume ambience", error)
            }
            return
        }
        try {
            val player = MediaPlayer.create(this, R.raw.aquarium_ambience) ?: return
            player.isLooping = true
            // The asset is already normalised; leave the final level to the TV's
            // media volume instead of stacking another 70% reduction on top.
            player.setVolume(0.70f, 0.70f)
            player.setOnErrorListener { _, _, _ ->
                player.release()
                if (ambience === player) ambience = null
                true
            }
            player.start()
            ambience = player
        } catch (error: Exception) {
            Log.w("Aquarium", "Could not start ambience", error)
        }
    }

    override fun onResume() {
        super.onResume()
        startOrResumeAmbience()
    }

    override fun onPause() {
        ambience?.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        ambience?.release()
        ambience = null
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

    private companion object {
        // Android starts repeating a held key after ~0.5 s. This is roughly one
        // second of holding OK, late enough that a normal feed tap never reaches it.
        const val LONG_PRESS_REPEAT = 10
    }
}
