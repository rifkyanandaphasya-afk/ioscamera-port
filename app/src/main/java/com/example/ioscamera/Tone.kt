package com.example.ioscamera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tone ala iPhone: bayangan sedikit terangkat, kontras lembut (S-curve),
 * highlight tidak pecah, warna "vibrant" (warna kulit dilindungi), sedikit hangat.
 */
object Tone {
    /** 0 = mati, 1 = iPhone, 2 = VIVID (warna pekat & kontras tegas ala GCam). */
    @Volatile var level = 1

    private fun raw(x: Float, lv: Int): Float {
        val g = if (lv == 2) 0.88f else 0.94f
        val sc = if (lv == 2) 0.42f else 0.30f
        var y = x.pow(g)
        val s = y * y * (3f - 2f * y)
        y += sc * (s - y)
        if (y > 0.85f) {
            val t = (y - 0.85f) / 0.15f
            y = 0.85f + 0.15f * (t - 0.1f * t * t)
        }
        return y
    }

    private val curves = Array(3) { lv ->
        val l = if (lv == 0) 1 else lv
        val white = raw(1f, l)
        FloatArray(256) { i -> (raw(i / 255f, l) / white).coerceIn(0f, 1f) }
    }

    private fun c8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    /** r,g,b (0..255) -> ARGB setelah kurva + vibrance + hangat. */
    fun toneRgb(ri: Int, gi: Int, bi: Int): Int {
        val lv = level
        val cv = curves[lv.coerceIn(0, 2)]
        var r = cv[ri]; var g = cv[gi]; var b = cv[bi]
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = if (mx > 1e-4f) (mx - mn) / mx else 0f
        var boost = if (lv == 2) 0.42f * (1f - sat) + 0.12f else 0.20f * (1f - sat) + 0.05f
        if (r >= g && g >= b && sat in 0.12f..0.65f) boost *= 0.5f // lindungi warna kulit
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r = l + (r - l) * (1f + boost)
        g = l + (g - l) * (1f + boost)
        b = l + (b - l) * (1f + boost)
        if (lv != 2) { r *= 1.012f; b *= 0.988f }
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
