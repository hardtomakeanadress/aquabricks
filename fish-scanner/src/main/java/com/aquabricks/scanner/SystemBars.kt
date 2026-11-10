package com.aquabricks.scanner

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps titles below the status bar on Android 15+ edge-to-edge devices. */
object SystemBars {

    fun padTop(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            target.setPadding(target.paddingLeft, top, target.paddingRight, target.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun padTopAndBottom(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(target.paddingLeft, bars.top, target.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun padBottom(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** For floating camera overlays that must keep their own background shape. */
    fun marginBelowStatusBar(view: View, baseMarginDp: Float) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val params = target.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                params.topMargin = PixelUi.dp(target.context, baseMarginDp) + top
                target.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun marginAboveNavigationBar(view: View, baseMarginDp: Float) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val params = target.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                params.bottomMargin = PixelUi.dp(target.context, baseMarginDp) + bottom
                target.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
