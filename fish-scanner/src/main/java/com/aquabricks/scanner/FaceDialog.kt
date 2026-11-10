package com.aquabricks.scanner

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aquabricks.scanner.imaging.ArgbImage

/**
 * Lets a child pick an eye and a mouth and drag them onto the scanned fish.
 * Models are premade pixel-art pieces; positions are normalised so they survive
 * later crop/grid changes.
 */
class FaceDialog(
    private val activity: Activity,
    private val base: ArgbImage,
    initialConfig: FaceConfig?,
    private val facingRight: Boolean,
    private val onApply: (FaceConfig) -> Unit
) {

    private val dialog = Dialog(activity)
    private var config: FaceConfig = initialConfig ?: FaceConfig(
        eyeX = if (facingRight) 0.70f else 0.30f,
        eyeY = 0.38f,
        mouthX = if (facingRight) 0.80f else 0.20f,
        mouthY = 0.62f
    )
    private lateinit var editor: FaceEditorView
    private val eyeChips = ArrayList<TextView>()
    private val mouthChips = ArrayList<TextView>()

    fun show() {
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(18f))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_panel)
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            label(activity.getString(R.string.face_title), 15f, R.color.accent, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(
            label(activity.getString(R.string.close), 9f, R.color.muted, true).apply {
                setPadding(dp(10f), dp(6f), 0, dp(6f))
                setOnClickListener { dialog.dismiss() }
            }
        )
        panel.addView(titleRow)
        panel.addView(
            label(activity.getString(R.string.face_hint), 8f, R.color.muted, false).apply {
                setPadding(0, dp(3f), 0, dp(10f))
            }
        )

        editor = FaceEditorView(activity).apply {
            background = ImageUtils.checkerDrawable(activity)
            setData(base, config, facingRight)
        }
        panel.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300f))
        )

        panel.addView(label(activity.getString(R.string.face_eyes), 9f, R.color.muted, true).apply {
            setPadding(0, dp(14f), 0, dp(6f))
        })
        panel.addView(chipScroller(buildEyeChips()))

        panel.addView(label(activity.getString(R.string.face_mouth), 9f, R.color.muted, true).apply {
            setPadding(0, dp(12f), 0, dp(6f))
        })
        panel.addView(chipScroller(buildMouthChips()))

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16f), 0, 0)
        }
        buttons.addView(
            ghostButton(activity.getString(R.string.face_cancel)).apply {
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(0, dp(50f), 0.42f).apply { marginEnd = dp(8f) }
        )
        buttons.addView(
            primaryButton(activity.getString(R.string.face_apply)).apply {
                setOnClickListener {
                    onApply(editor.currentConfig())
                    dialog.dismiss()
                }
            },
            LinearLayout.LayoutParams(0, dp(50f), 0.58f)
        )
        panel.addView(buttons)

        dialog.setContentView(
            ScrollView(activity).apply {
                isFillViewport = true
                addView(
                    panel,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
        dialog.setOnShowListener {
            val width = (activity.resources.displayMetrics.widthPixels * 0.94f).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun buildEyeChips(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        EyeModel.values().forEach { model ->
            val chip = PixelUi.chip(activity, model.label, config.eye == model) {
                // The editor is authoritative: dragging updates only its copy,
                // so copying the dialog's older config would reset the position.
                config = editor.currentConfig().copy(eye = model)
                PixelUi.selectOnly(eyeChips, model.ordinal)
                editor.setConfig(config)
            }
            eyeChips.add(chip)
            row.addView(chip)
        }
        return row
    }

    private fun buildMouthChips(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        MouthModel.values().forEach { model ->
            val chip = PixelUi.chip(activity, model.label, config.mouth == model) {
                config = editor.currentConfig().copy(mouth = model)
                PixelUi.selectOnly(mouthChips, model.ordinal)
                editor.setConfig(config)
            }
            mouthChips.add(chip)
            row.addView(chip)
        }
        return row
    }

    private fun chipScroller(row: View): View {
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                row,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
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

    private fun primaryButton(value: String): Button = Button(activity).apply {
        text = value
        isAllCaps = false
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(activity, R.color.deep))
        background = ContextCompat.getDrawable(activity, R.drawable.bg_primary)
        stateListAnimator = null
        minimumHeight = 0
    }

    private fun ghostButton(value: String): Button = Button(activity).apply {
        text = value
        isAllCaps = false
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(activity, R.color.cream))
        background = ContextCompat.getDrawable(activity, R.drawable.bg_ghost)
        stateListAnimator = null
        minimumHeight = 0
    }

    private fun dp(value: Float): Int = PixelUi.dp(activity, value)
}
