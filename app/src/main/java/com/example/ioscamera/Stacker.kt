package com.example.ioscamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Penggabungan multi-frame ala HDR+: sejajarkan beberapa foto beruntun lalu
 * rata-ratakan dengan penolakan "ghost" -> noise turun, detail & rentang dinamis naik.
 * Diproses per pita baris supaya hemat memori.
 */
object Stacker {
    private const val BAND = 128

    private class Small(val w: Int, val h: Int, val y: IntArray)

    private fun luma(p: Int) = (((p shr 16) and 255) * 54 + ((p shr 8) and 255) * 183 + (p and 255) * 19) shr 8

    private fun small(f: File): Small? {
        val bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        val s = Small(bm.width, bm.height, IntArray(px.size) { luma(px[it]) })
        bm.recycle()
        return s
    }

    private fun coarse(ref: Small, f: Small, r: Int): IntArray {
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        val m = r + 2
        for (dy in -r..r) for (dx in -r..r) {
            var sad = 0L
            var y = m
            while (y < ref.h - m) {
                val ro = y * ref.w
                val fo = (y + dy) * f.w + dx
                var x = m
                while (x < ref.w - m) { sad += abs(ref.y[ro + x] - f.y[fo + x]); x += 3 }
                y += 3
            }
            sad += (abs(dx) + abs(dy)) * 40L
            if (sad < best) { best = sad; bx = dx; by = dy }
        }
        return intArrayOf(bx, by)
    }

    private val opts get() = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

    private fun lumaRegion(d: BitmapRegionDecoder, r: Rect): IntArray? {
        val bm = d.decodeRegion(r, opts) ?: return null
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        bm.recycle()
        return IntArray(px.size) { luma(px[it]) }
    }

    /** Penyejajaran halus pada resolusi penuh (±5 px) di sekitar hasil kasar. */
    private fun refine(d0: BitmapRegionDecoder, di: BitmapRegionDecoder, w: Int, h: Int, cx: Int, cy: Int): IntArray? {
        val p = 512; val m = 6
        if (w < p + 2 * m || h < p + 2 * m) return intArrayOf(cx, cy)
        val px = (w - p) / 2; val py = (h - p) / 2
        if (px + cx - m < 0 || py + cy - m < 0 || px + cx + p + m > w || py + cy + p + m > h) return null
        val a = lumaRegion(d0, Rect(px, py, px + p, py + p)) ?: return null
        val b = lumaRegion(di, Rect(px + cx - m, py + cy - m, px + cx + p + m, py + cy + p + m)) ?: return null
        val bw = p + 2 * m
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        for (fy in -5..5) for (fx in -5..5) {
            var sad = 0L
            var j = 0
            while (j < p) {
                var i = 0
                while (i < p) { sad += abs(a[j * p + i] - b[(j + m + fy) * bw + i + m + fx]); i += 2 }
                j += 2
            }
            if (sad < best) { best = sad; bx = fx; by = fy }
        }
        return intArrayOf(cx + bx, cy + by)
    }

    private fun band(d: BitmapRegionDecoder, w: Int, h: Int, y0: Int, y1: Int, dx: Int, dy: Int): IntArray? {
        val top = (y0 + dy).coerceIn(0, h - 1)
        val bot = (y1 - 1 + dy).coerceIn(0, h - 1) + 1
        val bm = d.decodeRegion(Rect(0, top, w, bot), opts) ?: return null
        val rh = bm.height
        val src = IntArray(w * rh)
        bm.getPixels(src, 0, w, 0, 0, w, rh)
        bm.recycle()
        val out = IntArray(w * (y1 - y0))
        for (y in y0 until y1) {
            val sy = ((y + dy).coerceIn(0, h - 1) - top).coerceIn(0, rh - 1)
            val so = sy * w; val oo = (y - y0) * w
            for (x in 0 until w) out[oo + x] = src[so + (x + dx).coerceIn(0, w - 1)]
        }
        return out
    }

    fun merge(files: List<File>, out: File): Boolean {
        if (files.size < 2) return false
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].path, bo)
        val w = bo.outWidth; val h = bo.outHeight
        if (w < 200 || h < 200 || w.toLong() * h > 30_000_000L) return false

        val decs = ArrayList<BitmapRegionDecoder>()
        try {
            for (f in files) decs.add(BitmapRegionDecoder.newInstance(f.path, false)!!)
            val ref = small(files[0]) ?: return false
            val shifts = ArrayList<IntArray?>()
            shifts.add(intArrayOf(0, 0))
            for (i in 1 until files.size) {
                val s = small(files[i])
                if (s == null || s.w != ref.w || s.h != ref.h) { shifts.add(null); continue }
                val c = coarse(ref, s, 10)
                shifts.add(refine(decs[0], decs[i], w, h, c[0] * 4, c[1] * 4))
            }
            if (shifts.count { it != null } < 2) return false

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            var y0 = 0
            while (y0 < h) {
                val y1 = min(h, y0 + BAND); val rows = y1 - y0
                val refPx = band(decs[0], w, h, y0, y1, 0, 0) ?: return false
                val others = ArrayList<IntArray>()
                for (i in 1 until files.size) {
                    val s = shifts[i] ?: continue
                    band(decs[i], w, h, y0, y1, s[0], s[1])?.let { others.add(it) }
                }
                val res = IntArray(rows * w)
                for (k in res.indices) {
                    val p0 = refPx[k]
                    val r0 = (p0 shr 16) and 255; val g0 = (p0 shr 8) and 255; val b0 = p0 and 255
                    val l0 = (r0 * 54 + g0 * 183 + b0 * 19) shr 8
                    var sr = r0 * 16; var sg = g0 * 16; var sb = b0 * 16; var sw = 16
                    for (o in others) {
                        val p = o[k]
                        val rr = (p shr 16) and 255; val gg = (p shr 8) and 255; val bb = p and 255
                        val d = abs(((rr * 54 + gg * 183 + bb * 19) shr 8) - l0)
                        val wt = if (d < 12) 16 else if (d < 40) (40 - d) * 16 / 28 else 0
                        if (wt > 0) { sr += rr * wt; sg += gg * wt; sb += bb * wt; sw += wt }
                    }
                    res[k] = (0xFF shl 24) or ((sr / sw) shl 16) or ((sg / sw) shl 8) or (sb / sw)
                }
                bmp.setPixels(res, 0, w, 0, y0, w, rows)
                y0 = y1
            }
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            bmp.recycle()
            Enhancer.copyExif(files[0], out)
            return true
        } finally {
            for (d in decs) try { d.recycle() } catch (e: Exception) { }
        }
    }
}
