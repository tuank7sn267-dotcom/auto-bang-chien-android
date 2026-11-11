package vn.autobangchien

import android.graphics.Bitmap

/**
 * Một khung hình chụp từ màn hình. Mọi toạ độ truyền vào đều là toạ độ chuẩn 2400x1080,
 * tự quy đổi sang độ phân giải thật của điện thoại.
 */
class Frame(val w: Int, val h: Int, val px: IntArray) {
    companion object {
        const val HW = 1200
        const val HH = 540
        const val TW = 160
        const val TH = 72

        fun gray(c: Int): Int =
            ((((c shr 16) and 255) * 299) + (((c shr 8) and 255) * 587) + ((c and 255) * 114)) / 1000
    }

    /** Điểm ảnh ARGB tại toạ độ chuẩn (rx, ry). */
    fun rgb(rx: Int, ry: Int): Int {
        var sx = rx * w / Cfg.REF_W
        var sy = ry * h / Cfg.REF_H
        if (sx < 0) sx = 0
        if (sy < 0) sy = 0
        if (sx >= w) sx = w - 1
        if (sy >= h) sy = h - 1
        return px[sy * w + sx]
    }

    /** Ảnh xám 1200x540 (thu nhỏ một nửa, trung bình khối 2x2). */
    val half: IntArray by lazy { buildHalf() }

    private fun buildHalf(): IntArray {
        val out = IntArray(HW * HH)
        for (y in 0 until HH) {
            val sy0 = (2 * y) * h / Cfg.REF_H
            val sy1 = (2 * y + 1) * h / Cfg.REF_H
            for (x in 0 until HW) {
                val sx0 = (2 * x) * w / Cfg.REF_W
                val sx1 = (2 * x + 1) * w / Cfg.REF_W
                val s = gray(px[sy0 * w + sx0]) + gray(px[sy0 * w + sx1]) +
                    gray(px[sy1 * w + sx0]) + gray(px[sy1 * w + sx1])
                out[y * HW + x] = s / 4
            }
        }
        return out
    }

    /** Ảnh tích phân (tổng và tổng bình phương) để tính nhanh thống kê cửa sổ khi so khớp. */
    private val integ: LongArray by lazy { buildIntegral(false) }
    private val integ2: LongArray by lazy { buildIntegral(true) }

    private fun buildIntegral(square: Boolean): LongArray {
        val w1 = HW + 1
        val out = LongArray(w1 * (HH + 1))
        val g = half
        for (y in 1..HH) {
            var row = 0L
            for (x in 1..HW) {
                val v = g[(y - 1) * HW + (x - 1)].toLong()
                row += if (square) v * v else v
                out[y * w1 + x] = out[(y - 1) * w1 + x] + row
            }
        }
        return out
    }

    fun windowSum(x: Int, y: Int, tw: Int, th: Int): Long = win(integ, x, y, tw, th)

    fun windowSum2(x: Int, y: Int, tw: Int, th: Int): Long = win(integ2, x, y, tw, th)

    private fun win(a: LongArray, x: Int, y: Int, tw: Int, th: Int): Long {
        val w1 = HW + 1
        return a[(y + th) * w1 + (x + tw)] - a[y * w1 + (x + tw)] - a[(y + th) * w1 + x] + a[y * w1 + x]
    }

    /** Ảnh xám 160x72 để phát hiện màn hình đã đổi. */
    val thumb: IntArray by lazy { buildThumb() }

    private fun buildThumb(): IntArray {
        val g = half
        val out = IntArray(TW * TH)
        for (ty in 0 until TH) {
            val ys = ty * HH / TH
            for (tx in 0 until TW) {
                val xs = tx * HW / TW
                val s = g[(ys + 1) * HW + xs + 1] + g[(ys + 1) * HW + xs + 5] +
                    g[(ys + 5) * HW + xs + 1] + g[(ys + 5) * HW + xs + 5]
                out[ty * TW + tx] = s / 4
            }
        }
        return out
    }

    /** Độ khác biệt trung bình (0..255) giữa hai khung hình. */
    fun diff(o: Frame): Double {
        val a = thumb
        val b = o.thumb
        var s = 0L
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += if (d < 0) -d else d
        }
        return s.toDouble() / a.size
    }

    fun toBitmap(): Bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
