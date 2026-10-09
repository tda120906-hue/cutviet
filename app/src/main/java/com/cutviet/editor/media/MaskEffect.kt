package com.cutviet.editor.media

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.cutviet.editor.model.MaskShape
import com.cutviet.editor.model.ShapeMask

/**
 * Hides parts of an overlay: outside (or inside) a shape mask, and wherever the eraser was used.
 * The eraser is a PNG whose alpha says how much of each pixel stays visible.
 */
class MaskEffect(
    private val mask: ShapeMask?,
    private val erasePath: String?,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(mask, erasePath, useHdr)

    private class Program(private val mask: ShapeMask?, erasePath: String?, useHdr: Boolean) :
        BaseGlShaderProgram(useHdr, 1) {
        private val glProgram: GlProgram
        private var eraseTexId = -1
        private var aspect = 1f

        init {
            try {
                glProgram = GlProgram(VERTEX, FRAGMENT)
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
                erasePath?.let { path ->
                    BitmapFactory.decodeFile(path)?.let { bmp ->
                        eraseTexId = GlUtil.createTexture(bmp)
                        bmp.recycle()
                    }
                }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                if (eraseTexId != -1) {
                    glProgram.setSamplerTexIdUniform("uEraseSampler", eraseTexId, 1)
                    glProgram.setFloatUniform("uUseErase", 1f)
                } else {
                    glProgram.setFloatUniform("uUseErase", 0f)
                }
                val m = mask
                glProgram.setFloatUniform("uShape", m?.let { shapeIndex(it.shape) } ?: -1f)
                glProgram.setFloatUniform("uAspect", aspect)
                if (m != null) {
                    // Texture coordinates have y pointing up; the model measures from the top.
                    glProgram.setFloatsUniform("uCenter", floatArrayOf(m.cx, 1f - m.cy))
                    glProgram.setFloatUniform("uSize", m.size.coerceIn(0.02f, 2f))
                    glProgram.setFloatUniform("uFeather", m.feather.coerceIn(0f, 1f))
                    glProgram.setFloatUniform("uAngle", Math.toRadians(m.angle.toDouble()).toFloat())
                    glProgram.setFloatUniform("uRound", m.roundness.coerceIn(0f, 1f))
                    glProgram.setFloatUniform("uInvert", if (m.invert) 1f else 0f)
                }
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        /** Shape number used by the fragment shader. */
        private fun shapeIndex(shape: MaskShape): Float = when (shape) {
            MaskShape.CIRCLE -> 0f
            MaskShape.RECT -> 1f
            MaskShape.HEART -> 2f
            MaskShape.STAR -> 3f
            MaskShape.LINEAR -> 4f
            MaskShape.MIRROR -> 5f
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
                if (eraseTexId != -1) GlUtil.deleteTexture(eraseTexId)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform sampler2D uEraseSampler;
uniform float uUseErase;
uniform float uShape;
uniform float uAspect;
uniform vec2 uCenter;
uniform float uSize;
uniform float uFeather;
uniform float uAngle;
uniform float uRound;
uniform float uInvert;
varying vec2 vTexSamplingCoord;

float dot2(vec2 v) { return dot(v, v); }

float sdHeart(vec2 p) {
  p.x = abs(p.x);
  if (p.y + p.x > 1.0) return sqrt(dot2(p - vec2(0.25, 0.75))) - sqrt(2.0) / 4.0;
  return sqrt(min(dot2(p - vec2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
}

float sdStar5(vec2 p, float r, float rf) {
  vec2 k1 = vec2(0.809016994375, -0.587785252292);
  vec2 k2 = vec2(-k1.x, k1.y);
  p.x = abs(p.x);
  p -= 2.0 * max(dot(k1, p), 0.0) * k1;
  p -= 2.0 * max(dot(k2, p), 0.0) * k2;
  p.x = abs(p.x);
  p.y -= r;
  vec2 ba = rf * vec2(-k1.y, k1.x) - vec2(0.0, 1.0);
  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
  return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
}

// Signed distance to the shape edge (negative inside), in units of the frame height.
float shapeDistance(vec2 uv) {
  vec2 p = (uv - uCenter) * vec2(uAspect, 1.0);
  float c = cos(uAngle);
  float s = sin(uAngle);
  p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
  float hs = 0.5 * uSize * min(uAspect, 1.0);
  if (uShape < 0.5) return length(p) - hs;
  if (uShape < 1.5) {
    vec2 b = vec2(0.5 * uAspect, 0.5) * uSize;
    float r = uRound * min(b.x, b.y);
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
  }
  if (uShape < 2.5) {
    float k = 2.0 * hs / 1.1;
    return sdHeart(p / k + vec2(0.0, 0.55)) * k;
  }
  if (uShape < 3.5) return sdStar5(p, hs, 0.45);
  if (uShape < 4.5) return p.y;
  return abs(p.y) - 0.5 * uSize;
}

void main() {
  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
  float a = 1.0;
  if (uShape > -0.5) {
    float f = 0.002 + uFeather * 0.25;
    a = 1.0 - smoothstep(-f, f, shapeDistance(vTexSamplingCoord));
    if (uInvert > 0.5) a = 1.0 - a;
  }
  if (uUseErase > 0.5) {
    // Bitmaps are stored top row first, the opposite of texture coordinates.
    a *= texture2D(uEraseSampler, vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y)).a;
  }
  gl_FragColor = vec4(color.rgb, color.a * a);
}
"""
    }
}
