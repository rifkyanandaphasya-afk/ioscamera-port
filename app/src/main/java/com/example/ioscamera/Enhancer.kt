package com.example.ioscamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pemrosesan foto software: tone iPhone + "AI enhance"
 * (kontras lokal ala Smart HDR, angkat bayangan, penajaman dengan coring agar noise tidak ikut tajam).
 */
object Enhancer {
    private const val F = 32      // ukuran sel grid kontras lokal
    private const val BH = 64     // tinggi pita proses

    fun process(f: File, tone: Boolean, enhance: Boolean): Boolean {
        if (!tone && !enhance) return false
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bo)
        if (bo.outWidth <= 0 || bo.outWidth.toLong() * bo.outHeight > 60_000_000L) return false
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return false
        val w = bmp.width; val h = bmp.height
        val gw = (w + F - 1) / F; val gh = (h + F - 1) / F
        val grid = if (enhance) buildGrid(bmp, w, h, gw, gh) else null

        // indeks bilinear horizontal
        val gx0 = IntArray(w); val gx1 = IntArray(w); val gxt = FloatArray(w)
        for (x in 0 until w) {
            val g = (x + 0.5f) / F - 0.5f
            val i0 = g.toInt().coerceAtLeast(0)
            gx0[x] = min(i0, gw - 1); gx1[x] = min(i0 + 1, gw - 1)
            gxt[x] = (g - i0).coerceIn(0f, 1f)
        }

        val carry = IntArray(w)
        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + BH)
            val ys = max(0, y0 - 1); val ye = min(h, y1 + 1)
            val rows = ye - ys
            val px = IntArray(w * rows)
            bmp.getPixels(px, 0, w, 0, ys, w, rows)
            val lum = IntArray(w * rows)
            for (i in px.indices) {
                val p = px[i]
                lum[i] = (((p shr 16) and 255) * 54 + ((p shr 8) and 255) * 183 + (p and 255) * 19) shr 8
            }
            if (y0 > 0) System.arraycopy(carry, 0, lum, 0, w) // luma asli baris di atas pita
            System.arraycopy(lum, (y1 - 1 - ys) * w, carry, 0, w)

            for (y in y0 until y1) {
                val ry = y - ys
                val up = (if (y > 0) ry - 1 else ry) * w
                val dn = (if (y < h - 1) ry + 1 else ry) * w
                val cur = ry * w
                // baris grid
                val gy = (y + 0.5f) / F - 0.5f
                val j0 = gy.toInt().coerceAtLeast(0)
                val r0 = min(j0, gh - 1) * gw; val r1 = min(j0 + 1, gh - 1) * gw
                val ty = (gy - j0).coerceIn(0f, 1f)
                for (x in 0 until w) {
                    val p = px[cur + x]
                    var q = if (tone) Tone.toneRgb((p shr 16) and 255, (p shr 8) and 255, p and 255) else p
                    if (enhance && grid != null) {
                        val xl = if (x > 0) x - 1 else x
                        val xr = if (x < w - 1) x + 1 else x
                        val yc = lum[cur + x]
                        val avg = (lum[up + xl] + lum[up + x] + lum[up + xr] + lum[cur + xl] + yc +
                            lum[cur + xr] + lum[dn + xl] + lum[dn + x] + lum[dn + xr]) / 9f
                        var d = yc - avg
                        if (abs(d) < 3f) d *= 0.35f                     // coring: jangan pertajam noise
                        val a = gx0[x]; val bI = gx1[x]; val t = gxt[x]
                        val top = grid[r0 + a] * (1f - t) + grid[r0 + bI] * t
                        val bot = grid[r1 + a] * (1f - t) + grid[r1 + bI] * t
                        val lb = top * (1f - ty) + bot * ty             // kecerahan lokal 0..255
                        val yn = yc / 255f
                        val wgt = 4f * yn * (1f - yn)                   // fokus ke nada tengah
                        val delta = 0.55f * d + 0.22f * (yc - lb) * wgt
                        var gain = if (yc > 6) ((yc + delta) / yc).coerceIn(0.75f, 1.35f) else 1f
                        gain *= 1f + 0.12f * (max(0f, 110f - lb) / 110f) // angkat bayangan
                        var r = (((q shr 16) and 255) * gain + 0.5f).toInt()
                        var g = (((q shr 8) and 255) * gain + 0.5f).toInt()
                        var b = ((q and 255) * gain + 0.5f).toInt()
                        r = if (r > 255) 255 else r; g = if (g > 255) 255 else g; b = if (b > 255) 255 else b
                        q = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    px[cur + x] = q
                }
            }
            bmp.setPixels(px, (y0 - ys) * w, w, 0, y0, w, y1 - y0)
            y0 = y1
        }

        val tmp = File(f.path + ".t")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        bmp.recycle()
        copyExif(f, tmp)
        f.delete(); tmp.renameTo(f)
        return true
    }

    private fun buildGrid(bmp: Bitmap, w: Int, h: Int, gw: Int, gh: Int): FloatArray {
        val sum = FloatArray(gw * gh); val cnt = IntArray(gw * gh)
        var y0 = 0
        while (y0 < h) {
            val rows = min(BH, h - y0)
            val px = IntArray(w * rows)
            bmp.getPixels(px, 0, w, 0, y0, w, rows)
            var ry = 0
            while (ry < rows) {
                val cy = (y0 + ry) / F
                var x = 0
                while (x < w) {
                    val p = px[ry * w + x]
                    val l = (((p shr 16) and 255) * 54 + ((p shr 8) and 255) * 183 + (p and 255) * 19) shr 8
                    val i = cy * gw + x / F
                          sum[i] = sum[i] + l.toFloat()
cnt[i] = cnt[i] + 1
                    x += 2
                }
                ry += 2
            }
            y0 += rows
        }
        var g = FloatArray(gw * gh) { if (cnt[it] > 0) sum[it] / cnt[it] else 128f }
        repeat(2) {                       // blur kotak 3x3 dua kali
            val o = FloatArray(gw * gh)
            for (j in 0 until gh) for (i in 0 until gw) {
                var s = 0f; var n = 0
                for (dj in -1..1) for (di in -1..1) {
                    val jj = j + dj; val ii = i + di
                    if (jj in 0 until gh && ii in 0 until gw) { s += g[jj * gw + ii]; n++ }
                }
                o[j * gw + i] = s / n
            }
            g = o
        }
        return g
    }

    private fun copyExif(src: File, dst: File) {
        try {
            val a = ExifInterface(src.path); val b = ExifInterface(dst.path)
            val tags = listOf(
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM
            )
            for (t in tags) a.getAttribute(t)?.let { b.setAttribute(t, it) }
            b.saveAttributes()
        } catch (e: Exception) { /* abaikan */ }
    }
}
