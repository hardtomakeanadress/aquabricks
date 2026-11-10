package com.aquabricks.scanner

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

object PixelUi {

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            isSelected = selected
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text))
            background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11f
            setPadding(dp(context, 14f), dp(context, 8f), dp(context, 14f), dp(context, 8f))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 8f) }
            setOnClickListener {
                onClick()
            }
        }
    }

    fun selectOnly(chips: List<TextView>, selectedIndex: Int) {
        chips.forEachIndexed { index, chip -> chip.isSelected = index == selectedIndex }
    }

    fun setMargins(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        (view.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.setMargins(left, top, right, bottom)
            view.layoutParams = it
        }
    }
}
