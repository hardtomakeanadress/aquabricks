package com.aquabricks.scanner

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Bottom-sheet-style dialog that discovers tanks and uploads the sprite. Kept as
 * plain Views so the scanner APK stays small and builds quickly.
 */
class SendDialog(
    private val activity: Activity,
    private val png: ByteArray,
    private val fishName: String,
    private val creator: String,
    private val facing: String
) {

    private val dialog = Dialog(activity)
    private val prefs = activity.getSharedPreferences("aquabricks", Context.MODE_PRIVATE)

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refreshButton: TextView
    private lateinit var manualInput: EditText
    private lateinit var manualSendButton: Button

    private var discovering = false
    private var sending = false

    fun show() {
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setContentView(buildMainPanel())
        dialog.setOnShowListener {
            val metrics = activity.resources.displayMetrics
            dialog.window?.setLayout(
                (metrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
        discoverTanks()
    }

    private fun buildMainPanel(): View {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(20f))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_panel)
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label(activity.getString(R.string.send_dialog_title), 16f, R.color.accent, true)
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = label(activity.getString(R.string.close), 9f, R.color.muted, true).apply {
            setPadding(dp(10f), dp(6f), 0, dp(6f))
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(close)
        panel.addView(titleRow)

        val subtitleText = if (creator.isNotBlank()) {
            "${fishName.uppercase()}  -  MADE BY ${creator.uppercase()}"
        } else {
            fishName.uppercase()
        }
        panel.addView(
            label(subtitleText, 9f, R.color.muted, false).apply {
                setPadding(0, dp(2f), 0, dp(12f))
            }
        )

        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progress = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f))
            indeterminateTintList = ContextCompat.getColorStateList(activity, R.color.accent)
        }
        statusRow.addView(progress)
        statusText = label(activity.getString(R.string.send_dialog_looking), 9f, R.color.cream, false)
        statusRow.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
            }
        )
        refreshButton = label(activity.getString(R.string.refresh), 9f, R.color.accent, true).apply {
            setPadding(dp(10f), dp(6f), 0, dp(6f))
            setOnClickListener { discoverTanks() }
        }
        statusRow.addView(refreshButton)
        panel.addView(statusRow)

        listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(4f))
        }
        panel.addView(listContainer)

        panel.addView(
            label(activity.getString(R.string.send_dialog_manual), 9f, R.color.muted, true).apply {
                setPadding(0, dp(12f), 0, dp(6f))
            }
        )

        val manualRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        manualInput = EditText(activity).apply {
            hint = activity.getString(R.string.send_manual_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(activity, R.color.cream))
            setHintTextColor(ContextCompat.getColor(activity, R.color.muted))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_input)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setText(prefs.getString("last_address", "").orEmpty())
        }
        manualRow.addView(
            manualInput,
            LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginEnd = dp(8f) }
        )
        manualSendButton = primaryButton(activity.getString(R.string.send)).apply {
            setOnClickListener {
                val parsed = TankAddress.parse(manualInput.text.toString())
                if (parsed == null) {
                    statusText.text = "TYPE A TV ADDRESS LIKE 192.0.2.10:8765"
                    statusText.setTextColor(ContextCompat.getColor(activity, R.color.coral))
                } else {
                    sendTo(parsed.first, parsed.second, "the tank")
                }
            }
        }
        manualRow.addView(manualSendButton, LinearLayout.LayoutParams(dp(82f), dp(46f)))
        panel.addView(manualRow)

        panel.addView(
            label(
                "TIP: THE ADDRESS IS SHOWN AT THE BOTTOM-RIGHT OF THE AQUARIUM.",
                8f,
                R.color.muted,
                false
            ).apply { setPadding(0, dp(10f), 0, 0) }
        )
        panel.addView(
            label("MANAGE FISH IN THIS TANK", 9f, R.color.accent, true).apply {
                setPadding(0, dp(12f), 0, 0)
                setOnClickListener {
                    val parsed = TankAddress.parse(manualInput.text.toString())
                    if (parsed == null) {
                        statusText.text = "TYPE THE TV ADDRESS FIRST"
                        statusText.setTextColor(ContextCompat.getColor(activity, R.color.coral))
                    } else {
                        openManager(parsed.first, parsed.second, "the tank")
                    }
                }
            }
        )

        return ScrollView(activity).apply {
            isFillViewport = true
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun discoverTanks() {
        if (discovering || sending) return
        discovering = true
        statusText.text = activity.getString(R.string.send_dialog_looking)
        statusText.setTextColor(ContextCompat.getColor(activity, R.color.cream))
        progress.visibility = View.VISIBLE
        refreshButton.isEnabled = false
        listContainer.removeAllViews()

        TankDiscovery.discover(2400) { tanks ->
            if (!dialog.isShowing) return@discover
            discovering = false
            if (sending) return@discover
            progress.visibility = View.GONE
            refreshButton.isEnabled = true
            if (tanks.isEmpty()) {
                statusText.text = activity.getString(R.string.send_dialog_none)
                statusText.setTextColor(ContextCompat.getColor(activity, R.color.muted))
            } else {
                statusText.text = "FOUND YOUR AQUARIUM - TAP IT TO SEND"
                statusText.setTextColor(ContextCompat.getColor(activity, R.color.cream))
                if (manualInput.text.isNullOrBlank()) {
                    manualInput.setText("${tanks.first().host}:${tanks.first().port}")
                }
                tanks.forEach { addTankRow(it) }
            }
        }
    }

    private fun addTankRow(tank: TankDiscovery.Tank) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.bg_input)
            isClickable = true
            isFocusable = true
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
            setOnClickListener { sendTo(tank.host, tank.port, tank.name) }
        }
        row.addView(label(tank.name, 14f, R.color.cream, true))
        val infoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2f), 0, 0)
        }
        infoRow.addView(
            label(
                "${tank.host}:${tank.port}  -  ${tank.fish} fish",
                9f,
                R.color.muted,
                false
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        infoRow.addView(
            label("MANAGE", 9f, R.color.accent, true).apply {
                setPadding(dp(10f), dp(4f), 0, dp(4f))
                setOnClickListener { openManager(tank.host, tank.port, tank.name) }
            }
        )
        row.addView(infoRow)
        listContainer.addView(row)
    }

    private fun openManager(host: String, port: Int, tankLabel: String) {
        dialog.dismiss()
        activity.startActivity(
            Intent(activity, ManageTankActivity::class.java)
                .putExtra(ManageTankActivity.EXTRA_HOST, host)
                .putExtra(ManageTankActivity.EXTRA_PORT, port)
                .putExtra(ManageTankActivity.EXTRA_TANK_NAME, tankLabel)
        )
    }

    private fun sendTo(host: String, port: Int, tankLabel: String) {
        if (sending) return
        sending = true
        progress.visibility = View.VISIBLE
        statusText.text = activity.getString(R.string.sending)
        statusText.setTextColor(ContextCompat.getColor(activity, R.color.cream))
        refreshButton.isEnabled = false
        manualSendButton.isEnabled = false

        Thread({
            val outcome = TankClient.sendFish(host, port, fishName, creator, facing, png)
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                sending = false
                progress.visibility = View.GONE
                if (outcome.ok) {
                    prefs.edit().putString("last_address", "$host:$port").apply()
                    showSuccess(tankLabel, outcome.fishCount)
                } else {
                    manualSendButton.isEnabled = true
                    refreshButton.isEnabled = true
                    statusText.text = outcome.message
                    statusText.setTextColor(ContextCompat.getColor(activity, R.color.coral))
                }
            }
        }, "fish-upload").start()
    }

    private fun showSuccess(tankLabel: String, fishCount: Int) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_panel)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(label(activity.getString(R.string.your_fish_swims), 17f, R.color.accent, true).apply {
            gravity = Gravity.CENTER
        })
        panel.addView(
            label(
                "${fishName.uppercase()} IS NOW SWIMMING IN $tankLabel.",
                10f,
                R.color.cream,
                false
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10f), 0, 0)
            }
        )
        if (fishCount >= 0) {
            panel.addView(
                label(
                    "THE TANK REMEMBERS ${fishCount} FISH.",
                    8f,
                    R.color.muted,
                    false
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4f), 0, 0)
                }
            )
        }

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18f), 0, 0)
        }
        val scanAnother = primaryButton(activity.getString(R.string.scan_another)).apply {
            setOnClickListener {
                dialog.dismiss()
                activity.finish()
            }
        }
        val close = ghostButton(activity.getString(R.string.close)).apply {
            setOnClickListener { dialog.dismiss() }
        }
        buttons.addView(scanAnother, LinearLayout.LayoutParams(0, dp(50f), 1f).apply { marginEnd = dp(8f) })
        buttons.addView(close, LinearLayout.LayoutParams(0, dp(50f), 0.42f))
        panel.addView(buttons)
        panel.addView(
            label(
                "THE TANK KEEPS EVERY FISH, EVEN AFTER THE TV IS OFF.",
                8f,
                R.color.muted,
                false
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14f), 0, 0)
            }
        )

        dialog.setContentView(panel)
    }

    private fun label(value: String, sizeSp: Float, colorRes: Int, bold: Boolean): TextView {
        return TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(activity, colorRes))
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = 0.05f
        }
    }

    private fun primaryButton(text: String): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(activity, R.color.deep))
        background = ContextCompat.getDrawable(activity, R.drawable.bg_primary)
        stateListAnimator = null
        minimumHeight = 0
        setPadding(dp(10f), 0, dp(10f), 0)
    }

    private fun ghostButton(text: String): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(activity, R.color.cream))
        background = ContextCompat.getDrawable(activity, R.drawable.bg_ghost)
        stateListAnimator = null
        minimumHeight = 0
        setPadding(dp(10f), 0, dp(10f), 0)
    }

    private fun dp(value: Float): Int = PixelUi.dp(activity, value)
}
