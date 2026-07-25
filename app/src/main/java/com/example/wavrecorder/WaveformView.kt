package com.example.wavrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Rolling, smoothed waveform trace driven by amplitude samples pushed in during recording. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxSamples = 100
    private val amplitudes = ArrayDeque<Float>()

    // The displayed level eases toward each new amplitude reading instead of jumping straight
    // to it, so the trace moves as a gentle, fluid curve rather than a jittery, spiky one.
    private var smoothedAmplitude = 0f
    private val easing = 0.3f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D6200EE")
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6200EE")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
        strokeWidth = 2f
    }

    fun addAmplitude(amplitude: Float) {
        val target = amplitude.coerceIn(0f, 1f)
        smoothedAmplitude += (target - smoothedAmplitude) * easing
        amplitudes.addLast(smoothedAmplitude)
        while (amplitudes.size > maxSamples) amplitudes.removeFirst()
        invalidate()
    }

    fun clear() {
        amplitudes.clear()
        smoothedAmplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f

        canvas.drawLine(0f, midY, w, midY, baselinePaint)
        if (amplitudes.size < 2) return

        val slot = w / maxSamples
        val startIndex = maxSamples - amplitudes.size

        val topPoints = amplitudes.mapIndexed { i, amp ->
            val x = (startIndex + i) * slot + slot / 2f
            val halfBar = (amp * midY * 0.85f).coerceAtLeast(2f)
            x to (midY - halfBar)
        }
        val bottomPoints = topPoints.map { (x, y) -> x to (2 * midY - y) }

        val topPath = smoothPath(topPoints)
        val bottomPath = smoothPath(bottomPoints)

        val fillPath = Path(topPath)
        val reversedBottom = bottomPoints.asReversed()
        appendSmoothPathInto(fillPath, reversedBottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(topPath, strokePaint)
        canvas.drawPath(bottomPath, strokePaint)
    }

    /** Builds a smooth curve through [points], using each segment's midpoint as the bezier anchor. */
    private fun smoothPath(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        appendSmoothPathInto(path, points, startWithMoveTo = true)
        return path
    }

    private fun appendSmoothPathInto(
        path: Path,
        points: List<Pair<Float, Float>>,
        startWithMoveTo: Boolean = false
    ) {
        if (points.isEmpty()) return
        if (startWithMoveTo) path.moveTo(points[0].first, points[0].second)
        else path.lineTo(points[0].first, points[0].second)

        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            val midX = (x0 + x1) / 2f
            val midY = (y0 + y1) / 2f
            path.quadTo(x0, y0, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.first, last.second)
    }
}
