package com.droneedge.app.recording.calc.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the decoded video frame (OES external texture) full-screen, then blends a 2D RGBA
 * overlay texture (the boxes) on top. One [SurfaceTexture] feeds the OES texture.
 */
class FrameCompositor {

    val oesTextureId: Int
    val surfaceTexture: SurfaceTexture
    private val overlayTextureId: Int
    private val stMatrix = FloatArray(16)

    private val oesProgram: Int
    private val rgbaProgram: Int

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            )); position(0)
        }

    init {
        oesProgram = buildProgram(VERT, FRAG_OES)
        rgbaProgram = buildProgram(VERT, FRAG_RGBA)
        oesTextureId = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        overlayTextureId = genTexture(GLES20.GL_TEXTURE_2D)
        surfaceTexture = SurfaceTexture(oesTextureId)
    }

    /** Call after surfaceTexture.updateTexImage(); draws frame + overlay to the current EGL surface. */
    fun drawFrame(viewW: Int, viewH: Int, overlay: Bitmap) {
        surfaceTexture.getTransformMatrix(stMatrix)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawQuad(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId, stMatrix, blend = false)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
        drawQuad(rgbaProgram, GLES20.GL_TEXTURE_2D, overlayTextureId, IDENTITY, blend = true)
    }

    private fun drawQuad(program: Int, target: Int, texId: Int, texMatrix: FloatArray, blend: Boolean) {
        GLES20.glUseProgram(program)
        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        surfaceTexture.release()
        GLES20.glDeleteTextures(2, intArrayOf(oesTextureId, overlayTextureId), 0)
        GLES20.glDeleteProgram(oesProgram)
        GLES20.glDeleteProgram(rgbaProgram)
    }

    private fun genTexture(target: Int): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(target, t[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        const val VERT = """
            attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTexMatrix * aTex).xy; }
        """
        const val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
        const val FRAG_RGBA = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main() { vec4 c = texture2D(uTex, vTex); gl_FragColor = vec4(c.rgb * c.a, c.a); }
        """
    }
}
