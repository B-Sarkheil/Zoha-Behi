package com.behi.zoha

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView with pinch-zoom, double-tap zoom and panning.
 * When the image is not zoomed, a horizontal fling calls [onSwipe] (+1 = next, -1 = previous).
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onSwipe: ((Int) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private var baseScale = 1f
    private val maxZoomFactor = 5f
    private val doubleTapFactor = 2.5f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor)
                    .coerceIn(baseScale, baseScale * maxZoomFactor)
                val factor = target / current
                drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = drawMatrix
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isZoomed()) {
                    fitToView()
                } else {
                    val factor = doubleTapFactor
                    drawMatrix.postScale(factor, factor, e.x, e.y)
                    fixTranslation()
                    imageMatrix = drawMatrix
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (!isZoomed() || scaleDetector.isInProgress) return false
                drawMatrix.postTranslate(-dx, -dy)
                fixTranslation()
                imageMatrix = drawMatrix
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null || isZoomed() || scaleDetector.isInProgress) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) < 120 || Math.abs(dx) < Math.abs(dy)) return false
                onSwipe?.invoke(if (dx < 0) 1 else -1)
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fitToView()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun isZoomed(): Boolean = currentScale() > baseScale * 1.01f

    /** Resets the matrix so the whole image is visible and centered. */
    private fun fitToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        baseScale = Math.min(vw / dw, vh / dh)
        drawMatrix.reset()
        drawMatrix.postScale(baseScale, baseScale)
        drawMatrix.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f)
        imageMatrix = drawMatrix
    }

    /** Keeps the image inside the view (centered when smaller than the view). */
    private fun fixTranslation() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        val vw = width.toFloat()
        val vh = height.toFloat()
        var tx = 0f
        var ty = 0f
        tx = when {
            rect.width() <= vw -> (vw - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < vw -> vw - rect.right
            else -> 0f
        }
        ty = when {
            rect.height() <= vh -> (vh - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < vh -> vh - rect.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(tx, ty)
    }
}