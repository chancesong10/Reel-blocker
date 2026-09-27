package com.snoozy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

// Pomodoro-style ring: a soft track with a rounded progress arc on top.
class TimerRingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val stroke = 18f * resources.displayMetrics.density
    private val oval = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = context.getColor(R.color.track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    // 0..1, drawn clockwise from the top.
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var ringColor = context.getColor(R.color.coral)
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val inset = stroke / 2
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, trackPaint)
        if (progress > 0f) {
            arcPaint.color = ringColor
            canvas.drawArc(oval, -90f, 360f * progress, false, arcPaint)
        }
    }
}
