package com.example.bank

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class SpendingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E8EFED")
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4f * density), 0f)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#14A17A")
        strokeWidth = 2.3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#10A079")
        strokeWidth = 3f * density
    }

    private val linePath = Path()
    private val areaPath = Path()
    private val gridPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        areaPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.argb(59, 0x20, 0xA9, 0x83), // #20a983 at 0.23 opacity
            Color.argb(0, 0x20, 0xA9, 0x83),
            Shader.TileMode.CLAMP
        )

        val sx = w / 380f
        val sy = h / 100f

        gridPath.reset()
        listOf(20f, 52f, 84f).forEach { y ->
            gridPath.moveTo(0f, y * sy)
            gridPath.lineTo(w.toFloat(), y * sy)
        }

        linePath.reset()
        linePath.moveTo(0f * sx, 78f * sy)
        linePath.cubicTo(30f * sx, 74f * sy, 49f * sx, 51f * sy, 78f * sx, 59f * sy)
        linePath.cubicTo(107f * sx, 67f * sy, 120f * sx, 82f * sy, 150f * sx, 61f * sy)
        linePath.cubicTo(180f * sx, 40f * sy, 207f * sx, 44f * sy, 228f * sx, 52f * sy)
        linePath.cubicTo(249f * sx, 60f * sy, 274f * sx, 31f * sy, 300f * sx, 37f * sy)
        linePath.cubicTo(326f * sx, 43f * sy, 345f * sx, 18f * sy, 380f * sx, 15f * sy)

        areaPath.set(linePath)
        areaPath.lineTo(380f * sx, 100f * sy)
        areaPath.lineTo(0f, 100f * sy)
        areaPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        canvas.drawPath(gridPath, gridPaint)
        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(linePath, linePaint)

        val cx = 300f * (w / 380f)
        val cy = 37f * (h / 100f)
        val radius = 4f * density
        canvas.drawCircle(cx, cy, radius, dotFillPaint)
        canvas.drawCircle(cx, cy, radius, dotStrokePaint)
    }
}
