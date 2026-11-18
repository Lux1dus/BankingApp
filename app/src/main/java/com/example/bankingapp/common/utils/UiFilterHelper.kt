package com.example.bankingapp.common.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.example.bankingapp.R
import com.google.android.material.button.MaterialButton

object UiFilterHelper {

    /**
     * Highlights the active filter button with a solid accent color and white text,
     * while resetting all inactive buttons to a clean white outlined appearance.
     */
    fun highlightSelectedFilter(
        context: Context,
        selectedBtn: MaterialButton,
        allButtons: List<MaterialButton>,
        @ColorRes activeColorRes: Int = R.color.bank_primary
    ) {
        val activeColor = ContextCompat.getColor(context, activeColorRes)
        val borderColor = ContextCompat.getColor(context, R.color.bank_divider)
        val strokePx = (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

        allButtons.forEach { btn ->
            if (btn == selectedBtn) {
                btn.backgroundTintList = ColorStateList.valueOf(activeColor)
                btn.setTextColor(Color.WHITE)
                btn.strokeWidth = 0
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                btn.setTextColor(activeColor)
                btn.strokeColor = ColorStateList.valueOf(borderColor)
                btn.strokeWidth = strokePx
            }
        }
    }
}
