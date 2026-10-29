package com.omnidoc.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * ImageView with pinch-zoom and pan, used for PDF pages.
 * When the image is at 1x, vertical drags are forwarded to the parent
 * RecyclerView so normal document scrolling still works.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private var matrixValues = FloatArray(9)
    private val drawMatrix = Matrix()

    private var minScale = 1f
    private var maxScale = 4f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val next = (current * detector.scaleFactor).coerceIn(minScale * 0.9f, maxScale)
                drawMatrix.postScale(next / current, next / current, detector.focusX, detector.focusY)
                imageMatrix = drawMatrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() > minScale * 1.2f) minScale else minScale * 2.5f
                drawMatrix.postScale(target / currentScale(), target / currentScale(), e.x, e.y)
                imageMatrix = drawMatrix
                snapBackIfSmall()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = drawMatrix
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastTouchY = event.y
            MotionEvent.ACTION_MOVE -> {
                if (currentScale() <= minScale + 0.01f && !scaleDetector.isInProgress) {
                    // not zoomed: forward the drag to the parent list
                    val parent = findParentList()
                    parent?.scrollBy(0, (lastTouchY - event.y).toInt())
                }
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> snapBackIfSmall()
        }
        return true
    }

    private fun snapBackIfSmall() {
        if (currentScale() < minScale) {
            drawMatrix.reset()
            imageMatrix = drawMatrix
        }
    }

    private fun findParentList(): RecyclerView? {
        var p = parent
        while (p != null && p !is RecyclerView) p = (p as ViewGroup).parent
        return p as? RecyclerView
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        drawMatrix.reset()
        imageMatrix = drawMatrix
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }
}
