package com.aquabricks.scanner

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Launcher screen. The camera is an action, not the app's only mode: scanning and
 * managing the tank are both one tap away.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var lastTank: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        SystemBars.padTopAndBottom(findViewById(R.id.homeScroll))

        lastTank = findViewById(R.id.lastTank)
        findViewById<Button>(R.id.scanButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.manageButton).setOnClickListener {
            TankManagerLauncher.choose(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val last = TankManagerLauncher.lastAddress(this)
        lastTank.text = if (last == null) {
            getString(R.string.home_no_tank)
        } else {
            getString(R.string.home_last_tank, "${last.first}:${last.second}")
        }
    }
}
