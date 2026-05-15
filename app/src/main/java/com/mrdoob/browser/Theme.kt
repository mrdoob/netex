package com.mrdoob.browser

import android.view.View
import androidx.annotation.AttrRes
import com.google.android.material.color.MaterialColors

fun View.materialColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr)

fun View.materialColorHex(@AttrRes attr: Int): String =
    String.format("#%06X", 0xFFFFFF and materialColor(attr))
