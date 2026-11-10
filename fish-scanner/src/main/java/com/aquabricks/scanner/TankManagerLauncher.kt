package com.aquabricks.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Entry point shared by the home screen and the camera screen. Finds a tank,
 * remembers the last address, and opens the manager without requiring a scan.
 */
object TankManagerLauncher {

    private var choosing = false
    private var generation = 0

    fun choose(activity: Activity) {
        if (choosing) return
        choosing = true
        val request = ++generation
        var handled = false
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PixelUi.dp(activity, 20f), PixelUi.dp(activity, 18f),
                PixelUi.dp(activity, 20f), PixelUi.dp(activity, 18f))
        }
        panel.addView(
            ProgressBar(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    PixelUi.dp(activity, 24f), PixelUi.dp(activity, 24f)
                )
                indeterminateTintList = ContextCompat.getColorStateList(activity, R.color.accent)
            }
        )
        panel.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.manage_looking)
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(activity, R.color.cream))
                setPadding(PixelUi.dp(activity, 12f), 0, 0, 0)
            }
        )
        val looking = AlertDialog.Builder(activity)
            .setView(panel)
            .setCancelable(true)
            .create()
        looking.setOnDismissListener {
            choosing = false
            // A dismissed lookup must invalidate its own callback; otherwise it
            // can open the manager after the user has moved on to scanning.
            if (!handled) generation++
        }
        looking.show()

        TankDiscovery.discover(2400) { tanks ->
            val state = (activity as? androidx.lifecycle.LifecycleOwner)
                ?.lifecycle?.currentState
            if (request != generation || activity.isFinishing || activity.isDestroyed ||
                state == null || !state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            ) {
                return@discover
            }
            handled = true
            if (looking.isShowing) looking.dismiss()
            when {
                tanks.size == 1 -> open(activity, tanks[0])
                tanks.size > 1 -> showTankChooser(activity, tanks)
                // A stale saved address must not trap the user on a dead endpoint.
                // Always offer the manual field, prefilled with the last address.
                else -> showAddressDialog(activity)
            }
        }
    }

    fun open(activity: Activity, tank: TankDiscovery.Tank) =
        open(activity, tank.host, tank.port, tank.name)

    fun open(activity: Activity, host: String, port: Int, name: String) {
        prefs(activity).edit().putString("last_address", "$host:$port").apply()
        activity.startActivity(
            Intent(activity, ManageTankActivity::class.java)
                .putExtra(ManageTankActivity.EXTRA_HOST, host)
                .putExtra(ManageTankActivity.EXTRA_PORT, port)
                .putExtra(ManageTankActivity.EXTRA_TANK_NAME, name)
        )
    }

    private fun showTankChooser(activity: Activity, tanks: List<TankDiscovery.Tank>) {
        val labels = tanks.map { "${it.name}  -  ${it.fish} fish" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.manage_choose)
            .setItems(labels) { _, which -> open(activity, tanks[which]) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showAddressDialog(activity: Activity) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.send_manual_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            textSize = 14f
            setText(prefs(activity).getString("last_address", "").orEmpty())
            setSelectAllOnFocus(true)
            setTextColor(ContextCompat.getColor(activity, R.color.cream))
            setHintTextColor(ContextCompat.getColor(activity, R.color.muted))
            setPadding(PixelUi.dp(activity, 12f), PixelUi.dp(activity, 10f),
                PixelUi.dp(activity, 12f), PixelUi.dp(activity, 10f))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.manage_address_title)
            .setMessage(R.string.manage_no_tank)
            .setView(input)
            .setPositiveButton(R.string.manage_connect) { _, _ ->
                val parsed = TankAddress.parse(input.text.toString())
                if (parsed == null) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.manage_invalid_address),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    open(activity, parsed.first, parsed.second, "the tank")
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun lastAddress(activity: Activity): Pair<String, Int>? =
        TankAddress.parse(prefs(activity).getString("last_address", "").orEmpty())

    private fun prefs(activity: Activity) =
        activity.getSharedPreferences("aquabricks", Context.MODE_PRIVATE)
}
