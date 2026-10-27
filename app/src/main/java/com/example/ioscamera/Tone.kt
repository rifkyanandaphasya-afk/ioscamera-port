package com.example.ioscamera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tone ala iPhone: bayangan sedikit terangkat, kontras lembut (S-curve),
 * highlight tidak pecah, warna "vibrant" (warna kulit dilindungi), sedikit hangat.
 */
object Tone {
    private fun raw(x: Float): Float {
        var y = x.pow(0.94f)
        val s = y * y * (3f - 2f * y)
        y += 0.30f * (s - y)
        if (y > 0.85f) {
            val t = (y - 0.85f) / 0.15f
            y = 0.85f + 0.15f * (t - 0.1f * t * t)
        }
        return y
    }

    private val white = raw(1f)
    val curve = FloatArray(256) { i -> (raw(i / 255f) / white).coerceIn(0f, 1f) }

    private fun c8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    /** r,g,b (0..255) -> ARGB setelah kurva + vibrance + hangat. */
    fun toneRgb(ri: Int, gi: Int, bi: Int): Int {
        var r = curve[ri]; var g = curve[gi]; var b = curve[bi]
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = if (mx > 1e-4f) (mx - mn) / mx else 0f
        var boost = 0.20f * (1f - sat) + 0.05f
        if (r >= g && g >= b && sat in 0.12f..0.65f) boost *= 0.45f // lindungi warna kulit
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = l + (r - l) * (1f + boost)
        g = l + (g - l) * (1f + boost)
        b = l + (b - l) * (1f + boost)
        r *= 1.012f; b *= 0.988f
        return (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
    }

    /** LUT 3D untuk video (Media3 SingleColorLut), indeks [r][g][b]. */
    fun cube(n: Int = 33): Array<Array<IntArray>> = Array(n) { r ->
        Array(n) { g ->
            IntArray(n) { b ->
                toneRgb(r * 255 / (n - 1), g * 255 / (n - 1), b * 255 / (n - 1))
            }
        }
    }
}
