package com.cutviet.editor.media

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Makes pixels close to a key colour transparent (green/blue screen removal).
 * Distance is measured on chroma (Cb/Cr) so shadows on the screen are removed too.
 */
class ChromaKeyEffect(
    private val keyColor: Int,
    private val strength: Float,
    private val softness: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(keyColor, strength, softness, useHdr)

    private class Program(keyColor: Int, strength: Float, softness: Float, useHdr: Boolean) :
        BaseGlShaderProgram(useHdr, 1) {
        private val glProgram: GlProgram
        private val key = floatArrayOf(
            ((keyColor shr 16) and 0xFF) / 255f,
            ((keyColor shr 8) and 0xFF) / 255f,
            (keyColor and 0xFF) / 255f,
        )
        // Chroma distances are small (max ~0.7), so map the 0..1 slider onto a useful range.
        private val threshold = 0.02f + strength.coerceIn(0f, 1f) * 0.4f
        private val smooth = 0.005f + softness.coerceIn(0f, 1f) * 0.2f

        init {
            try {
                glProgram = GlProgram(VERTEX, FRAGMENT)
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniform("uKey", key)
                glProgram.setFloatUniform("uThreshold", threshold)
                glProgram.setFloatUniform("uSmooth", smooth)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
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
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec3 uKey;
uniform float uThreshold;
uniform float uSmooth;
varying vec2 vTexSamplingCoord;
vec2 chroma(vec3 c) {
  return vec2(-0.169 * c.r - 0.331 * c.g + 0.5 * c.b, 0.5 * c.r - 0.419 * c.g - 0.081 * c.b);
}
void main() {
  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
  float d = distance(chroma(color.rgb), chroma(uKey));
  float alpha = smoothstep(uThreshold, uThreshold + uSmooth, d);
  // Reduce colour spill from the screen on the remaining edges.
  vec3 rgb = color.rgb;
  if (alpha < 1.0) {
    float gray = dot(rgb, vec3(0.299, 0.587, 0.114));
    rgb = mix(vec3(gray), rgb, alpha);
  }
  gl_FragColor = vec4(rgb, color.a * alpha);
}
"""
    }
}
