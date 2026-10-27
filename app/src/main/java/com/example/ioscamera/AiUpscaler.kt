package com.example.ioscamera

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Real-ESRGAN (TFLite) per-ubin. Model dibaca dari assets (file .tflite pertama yang ditemukan).
 * Bentuk input/output dibaca dari model (diharapkan NHWC float32, mis. 128x128 -> 512x512).
 */
class AiUpscaler(private val ctx: Context) {
    private var interp: Interpreter? = null
    private var inW = 128
    private var inH = 128
    private var scale = 4
    var error: String? = null

    private fun modelName(): String? =
        try { ctx.assets.list("")?.firstOrNull { it.endsWith(".tflite") } } catch (e: Exception) { null }

    fun available(): Boolean = modelName() != null

    private fun load(): Boolean {
        if (interp != null) return true
        val name = modelName() ?: run { error = "file .tflite tidak ada di assets"; return false }
        return try {
            val bytes = ctx.assets.open(name).use { it.readBytes() }
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            bb.put(bytes); bb.rewind()
            val ip = Interpreter(bb, Interpreter.Options().setNumThreads(4))
            val si = ip.getInputTensor(0).shape()
            val so = ip.getOutputTensor(0).shape()
            if (si.size != 4 || si[3] != 3 || so.size != 4 || so[3] != 3 ||
                ip.getInputTensor(0).dataType() != DataType.FLOAT32) {
                error = "format model tidak didukung (butuh NHWC float32), bentuk=${si.joinToString("x")}"
                ip.close(); return false
            }
            inH = si[1]; inW = si[2]; scale = so[1] / si[1]
            interp = ip
            true
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** Perbesar [src] sebesar kelipatan model (umumnya 4x). Hasil: bitmap (w*scale) x (h*scale). */
    fun upscale(src: Bitmap, onProgress: (Int, Int) -> Unit): Bitmap? {
        if (!load()) return null
        val ip = interp!!
        val s = scale
        val pad = 8
        val stepW = inW - 2 * pad; val stepH = inH - 2 * pad
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w * s, h * s, Bitmap.Config.ARGB_8888)
        val inBuf = ByteBuffer.allocateDirect(inH * inW * 3 * 4).order(ByteOrder.nativeOrder())
        val oW = inW * s
        val outBuf = ByteBuffer.allocateDirect(inH * s * oW * 3 * 4).order(ByteOrder.nativeOrder())
        val tx = (w + stepW - 1) / stepW; val ty = (h + stepH - 1) / stepH
        var done = 0
        for (j in 0 until ty) for (i in 0 until tx) {
            val x0 = i * stepW; val y0 = j * stepH
            inBuf.rewind()
            val fb = inBuf.asFloatBuffer()
            for (yy in 0 until inH) {
                val sy = (y0 - pad + yy).coerceIn(0, h - 1)
                for (xx in 0 until inW) {
                    val sx = (x0 - pad + xx).coerceIn(0, w - 1)
                    val p = px[sy * w + sx]
                    fb.put(((p shr 16) and 255) / 255f)
                    fb.put(((p shr 8) and 255) / 255f)
                    fb.put((p and 255) / 255f)
                }
            }
            outBuf.rewind()
            ip.run(inBuf, outBuf)
            outBuf.rewind()
            val of = outBuf.asFloatBuffer()
            val tw = min(stepW, w - x0); val th = min(stepH, h - y0)
            val ow = tw * s; val oh = th * s
            val res = IntArray(ow * oh)
            for (oy in 0 until oh) for (ox in 0 until ow) {
                val idx = ((pad * s + oy) * oW + (pad * s + ox)) * 3
                val r = (of.get(idx) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (of.get(idx + 1) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (of.get(idx + 2) * 255f + 0.5f).toInt().coerceIn(0, 255)
                res[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(res, 0, ow, x0 * s, y0 * s, ow, oh)
            done++
            onProgress(done, tx * ty)
        }
        return out
    }
}
