package com.aquabricks.scanner

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Lists the fish stored on the TV and lets a grown-up remove one, so the 64-fish
 * cap never becomes a dead end. Reached from the send dialog.
 */
class ManageTankActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TANK_NAME = "tank_name"
    }

    private lateinit var host: String
    private var port: Int = 8765
    private lateinit var tankName: String

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var refreshButton: TextView

    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tank-manager").apply { isDaemon = true }
    }
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: run {
            finish()
            return
        }
        port = intent.getIntExtra(EXTRA_PORT, 8765)
        tankName = intent.getStringExtra(EXTRA_TANK_NAME) ?: "AquaBricks tank"
        val root = buildRoot()
        setContentView(root)
        SystemBars.padTopAndBottom(root)
        refresh()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ManageTankActivity, R.color.deep))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(6f), dp(12f), dp(6f))
        }
        val back = label(getString(R.string.back_arrow), 30f, R.color.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setOnClickListener { finish() }
        }
        toolbar.addView(back)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(label("MANAGE THE TANK", 15f, R.color.cream, true))
        titles.addView(
            label("$tankName  -  $host:$port", 8f, R.color.muted, false).apply {
                setPadding(0, dp(2f), 0, 0)
            }
        )
        toolbar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshButton = label("REFRESH", 9f, R.color.accent, true).apply {
            setPadding(dp(10f), dp(8f), 0, dp(8f))
            setOnClickListener { refresh() }
        }
        toolbar.addView(refreshButton)
        root.addView(toolbar)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(4f), dp(18f), dp(8f))
        }
        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f))
            indeterminateTintList = ContextCompat.getColorStateList(this@ManageTankActivity, R.color.accent)
        }
        statusRow.addView(progress)
        statusText = label("READING THE TANK...", 9f, R.color.cream, false).apply {
            setPadding(dp(8f), 0, 0, 0)
        }
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusRow)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(24f))
        }
        scroll.addView(
            listContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll)
        return root
    }

    private fun refresh() {
        if (loading) return
        loading = true
        progress.visibility = View.VISIBLE
        statusText.text = "READING THE TANK..."
        statusText.setTextColor(ContextCompat.getColor(this, R.color.cream))
        refreshButton.isEnabled = false
        listContainer.removeAllViews()
        loader.execute {
            val fish = TankClient.listFish(host, port)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                progress.visibility = View.GONE
                refreshButton.isEnabled = true
                if (fish == null) {
                    statusText.text = "COULD NOT REACH THE TANK. CHECK WI-FI AND TRY REFRESH."
                    statusText.setTextColor(ContextCompat.getColor(this, R.color.coral))
                } else {
                    renderFish(fish)
                }
            }
        }
    }

    private fun renderFish(fish: List<TankClient.FishSummary>) {
        listContainer.removeAllViews()
        if (fish.isEmpty()) {
            statusText.text = "NO FISH IN THE TANK YET."
            statusText.setTextColor(ContextCompat.getColor(this, R.color.muted))
            return
        }
        statusText.text = "${fish.size} FISH - REMOVE ONE TO MAKE ROOM"
        statusText.setTextColor(ContextCompat.getColor(this, R.color.cream))
        for (summary in fish) listContainer.addView(fishRow(summary))
    }

    private fun fishRow(summary: TankClient.FishSummary): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@ManageTankActivity, R.drawable.bg_input)
            setPadding(dp(14f), dp(12f), dp(10f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(label(summary.name, 14f, R.color.cream, true))
        textColumn.addView(
            label(
                if (summary.creator.isBlank()) "NO MAKER" else "MADE BY ${summary.creator.uppercase()}",
                8f,
                R.color.muted,
                false
            ).apply { setPadding(0, dp(2f), 0, 0) }
        )
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val remove = label("REMOVE", 9f, R.color.coral, true).apply {
            setPadding(dp(12f), dp(10f), dp(4f), dp(10f))
            setOnClickListener { confirmDelete(summary) }
        }
        row.addView(remove)
        return row
    }

    private fun confirmDelete(summary: TankClient.FishSummary) {
        AlertDialog.Builder(this)
            .setTitle("REMOVE ${summary.name.uppercase()}?")
            .setMessage("This removes the fish from the tank on the TV.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("REMOVE") { _, _ -> delete(summary) }
            .show()
    }

    private fun delete(summary: TankClient.FishSummary) {
        statusText.text = "REMOVING ${summary.name.uppercase()}..."
        statusText.setTextColor(ContextCompat.getColor(this, R.color.cream))
        loader.execute {
            val deleted = TankClient.deleteFish(host, port, summary.id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (deleted) {
                    Toast.makeText(this, "${summary.name} is out of the tank.", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    statusText.text = "COULD NOT REMOVE THAT FISH. TRY REFRESH."
                    statusText.setTextColor(ContextCompat.getColor(this, R.color.coral))
                }
            }
        }
    }

    private fun label(value: String, sizeSp: Float, colorRes: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(this@ManageTankActivity, colorRes))
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = 0.05f
        }
    }

    private fun dp(value: Float): Int = PixelUi.dp(this, value)
}
