package vn.autobangchien

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Làm mờ hộp 3x3 (biên lặp lại). Giúp so khớp bớt nhạy với lệch dưới pixel khi thu phóng/dịch lẻ. */
fun blur3(d: IntArray, w: Int, h: Int): IntArray {
    val tmp = IntArray(w * h)
    for (y in 0 until h) {
        val r = y * w
        for (x in 0 until w) {
            val a = d[r + (if (x > 0) x - 1 else 0)]
            val b = d[r + x]
            val c = d[r + (if (x < w - 1) x + 1 else w - 1)]
            tmp[r + x] = a + b + c
        }
    }
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val y0 = (if (y > 0) y - 1 else 0) * w
        val y1 = y * w
        val y2 = (if (y < h - 1) y + 1 else h - 1) * w
        for (x in 0 until w) {
            out[y1 + x] = (tmp[y0 + x] + tmp[y1 + x] + tmp[y2 + x]) / 9
        }
    }
    return out
}

/** Một mặt phẳng ảnh xám kèm ảnh tích phân để tính nhanh thống kê cửa sổ khi so khớp. */
class Plane(val w: Int, val h: Int, val d: IntArray) {
    private val integ: LongArray by lazy { build(false) }
    private val integ2: LongArray by lazy { build(true) }

    private fun build(square: Boolean): LongArray {
        val w1 = w + 1
        val out = LongArray(w1 * (h + 1))
        for (y in 1..h) {
            var row = 0L
            for (x in 1..w) {
                val v = d[(y - 1) * w + (x - 1)].toLong()
                row += if (square) v * v else v
                out[y * w1 + x] = out[(y - 1) * w1 + x] + row
            }
        }
        return out
    }

    fun sum(x: Int, y: Int, tw: Int, th: Int): Long = win(integ, x, y, tw, th)

    fun sum2(x: Int, y: Int, tw: Int, th: Int): Long = win(integ2, x, y, tw, th)

    private fun win(a: LongArray, x: Int, y: Int, tw: Int, th: Int): Long {
        val w1 = w + 1
        return a[(y + th) * w1 + (x + tw)] - a[y * w1 + (x + tw)] - a[(y + th) * w1 + x] + a[y * w1 + x]
    }
}

/**
 * Một khung hình chụp từ màn hình thật (w x h). `scale` là tỉ lệ so với màn hình chuẩn cao 1080
 * (mặc định h/1080). Mọi toạ độ ở đây là "toạ độ chuẩn": cao 1080, rộng refW (tuỳ tỉ lệ màn hình).
 */
class Frame(val w: Int, val h: Int, val px: IntArray, val scale: Double) {
    companion object {
        const val HH = 540
        const val TW = 160
        const val TH = 72

        fun gray(c: Int): Int =
            ((((c shr 16) and 255) * 299) + (((c shr 8) and 255) * 587) + ((c and 255) * 114)) / 1000
    }

    val refW: Int = (w / scale).roundToInt()
    val hw: Int = refW / 2

    private fun clampX(v: Int): Int = if (v < 0) 0 else if (v >= w) w - 1 else v

    private fun clampY(v: Int): Int = if (v < 0) 0 else if (v >= h) h - 1 else v

    /** Điểm ảnh ARGB tại toạ độ chuẩn (rx, ry). */
    fun rgb(rx: Int, ry: Int): Int = px[clampY((ry * scale).toInt()) * w + clampX((rx * scale).toInt())]

    fun rescaled(newScale: Double): Frame = Frame(w, h, px, newScale)

    /** Ảnh xám thu nhỏ một nửa (rộng hw, cao 540), trung bình khối 2x2. */
    val half: Plane by lazy { buildHalf() }

    private fun buildHalf(): Plane {
        val ow = hw
        val out = IntArray(ow * HH)
        val xs0 = IntArray(ow)
        val xs1 = IntArray(ow)
        for (x in 0 until ow) {
            xs0[x] = clampX(((2 * x) * scale).toInt())
            xs1[x] = clampX(((2 * x + 1) * scale).toInt())
        }
        for (y in 0 until HH) {
            val r0 = clampY(((2 * y) * scale).toInt()) * w
            val r1 = clampY(((2 * y + 1) * scale).toInt()) * w
            for (x in 0 until ow) {
                val s = gray(px[r0 + xs0[x]]) + gray(px[r0 + xs1[x]]) +
                    gray(px[r1 + xs0[x]]) + gray(px[r1 + xs1[x]])
                out[y * ow + x] = s / 4
            }
        }
        return Plane(ow, HH, blur3(out, ow, HH))
    }

    /** Thu nhỏ thêm một nửa nữa (1/4), dùng để dò thô trên toàn bề ngang. */
    val quarter: Plane by lazy { buildQuarter() }

    private fun buildQuarter(): Plane {
        val hp = half
        val qw = hp.w / 2
        val qh = hp.h / 2
        val out = IntArray(qw * qh)
        for (y in 0 until qh) {
            for (x in 0 until qw) {
                val i = (2 * y) * hp.w + 2 * x
                out[y * qw + x] = (hp.d[i] + hp.d[i + 1] + hp.d[i + hp.w] + hp.d[i + hp.w + 1]) / 4
            }
        }
        return Plane(qw, qh, out)
    }

    /** Ảnh xám 160x72 để phát hiện màn hình đã đổi. */
    val thumb: IntArray by lazy { buildThumb() }

    private fun buildThumb(): IntArray {
        val hp = half
        val g = hp.d
        val out = IntArray(TW * TH)
        for (ty in 0 until TH) {
            val ys = ty * HH / TH
            val y1 = minOf(HH - 1, ys + 1)
            val y5 = minOf(HH - 1, ys + 5)
            for (tx in 0 until TW) {
                val xs = tx * hp.w / TW
                val x1 = minOf(hp.w - 1, xs + 1)
                val x5 = minOf(hp.w - 1, xs + 5)
                val s = g[y1 * hp.w + x1] + g[y1 * hp.w + x5] + g[y5 * hp.w + x1] + g[y5 * hp.w + x5]
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
