package com.threejs.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

class DragInterceptLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onDragStart: (() -> Unit)? = null
    var onDragMove: ((dyPx: Float) -> Unit)? = null
    var onDragEnd: ((dyPx: Float) -> Unit)? = null

    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.rawY - downY) > slop) {
                    dragging = true
                    onDragStart?.invoke()
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // No child consumed DOWN — claim it so MOVE/UP still arrive,
                // letting empty-background drags work.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.rawY - downY) > slop) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) {
                    onDragMove?.invoke(ev.rawY - downY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    onDragEnd?.invoke(ev.rawY - downY)
                    dragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
