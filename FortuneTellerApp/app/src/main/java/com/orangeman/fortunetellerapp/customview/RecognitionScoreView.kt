package com.orangeman.fortunetellerapp.customview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.orangeman.fortunetellerapp.customview.domain.Recognition


class RecognitionScoreView(
    context: Context?,
    set: AttributeSet?
) :
    View(context, set), ResultsView {
    private val textSizePx: Float
    private val fgPaint: Paint
    private val bgPaint: Paint
    private lateinit var results: List<Recognition>

    override fun setResults(results: List<Recognition>) {
        this.results = results
        postInvalidate()
    }

    public override fun onDraw(canvas: Canvas) {
        val x = 10
        var y = (fgPaint.textSize * 1.5f).toInt()
        canvas.drawPaint(bgPaint)
        if (results != null) {
            for (recog in results!!) {
                canvas.drawText(
                    recog.title.toString() + ": " + recog.confidence,
                    x.toFloat(),
                    y.toFloat(),
                    fgPaint
                )
                y += (fgPaint.textSize * 1.5f).toInt()
            }
        }
    }

    companion object {
        private const val TEXT_SIZE_DIP = 16f
    }

    init {
        textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            TEXT_SIZE_DIP,
            resources.displayMetrics
        )
        fgPaint = Paint()
        fgPaint.textSize = textSizePx
        bgPaint = Paint()
        bgPaint.color = -0x33bd7a0c
    }

}
