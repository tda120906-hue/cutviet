package com.cutviet.editor.media

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation
import com.cutviet.editor.model.CropOps

/**
 * Turns the picture clockwise by a small angle and enlarges it just enough to keep the frame
 * filled, keeping the frame size ("chỉnh nghiêng" in the crop editor).
 */
class StraightenTransformation(private val degrees: Float) : MatrixTransformation {
    private var matrix = Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val m = CropOps.straightenMatrix(degrees, inputWidth.toFloat() / inputHeight.coerceAtLeast(1))
        matrix = Matrix().apply { setValues(floatArrayOf(m[0], m[1], 0f, m[2], m[3], 0f, 0f, 0f, 1f)) }
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix = matrix
}
