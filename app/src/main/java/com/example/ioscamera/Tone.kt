package com.example.ioscamera

/** Tone ala iPhone: sedikit lebih hangat, saturasi +12%, kontras +4%. */
object Tone {
    private const val C = 1.04f
    private const val S = 1.12f
    private val W = floatArrayOf(1.015f, 1.0f, 0.985f)
    private const val OFF = 0.5f * (1f - C)

    private fun m(): Array<FloatArray> {
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val sat = arrayOf(
            floatArrayOf(lr * (1 - S) + S, lg * (1 - S), lb * (1 - S)),
            floatArrayOf(lr * (1 - S), lg * (1 - S) + S, lb * (1 - S)),
            floatArrayOf(lr * (1 - S), lg * (1 - S), lb * (1 - S) + S)
        )
        return Array(3) { r -> FloatArray(3) { c -> C * W[r] * sat[r][c] } }
    }

    /** android.graphics.ColorMatrix (4x5, baris-utama, offset 0..255). */
    fun photo(): FloatArray {
        val m = m(); val o = OFF * 255f
        return floatArrayOf(
            m[0][0], m[0][1], m[0][2], 0f, o,
            m[1][0], m[1][1], m[1][2], 0f, o,
            m[2][0], m[2][1], m[2][2], 0f, o,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** Media3 RgbMatrix (4x4, kolom-utama). */
    fun video(): FloatArray {
        val m = m()
        return floatArrayOf(
            m[0][0], m[1][0], m[2][0], 0f,
            m[0][1], m[1][1], m[2][1], 0f,
            m[0][2], m[1][2], m[2][2], 0f,
            OFF, OFF, OFF, 1f
        )
    }
}
