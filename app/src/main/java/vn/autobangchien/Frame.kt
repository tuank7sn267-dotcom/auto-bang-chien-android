package vn.autobangchien

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Một khung hình chụp từ màn hình. Toạ độ logic luôn theo canvas chuẩn 2400x1080. */
class Frame(val w: Int, val h: Int, val px: IntArray) {
    companion object {
        const val HW = 1200
        const val HH = 540
        const val TW = 160
        const val TH = 72

        fun gray(c: Int): Int =
            ((((c shr 16) and 255) * 299) + (((c shr 8) and 255) * 587) + ((c and 255) * 114)) / 1000
    }

    /** Tự bỏ viền đen thật sự (letterbox/pillarbox), không cắt nền game tối thông thường. */
    private val content: IntArray by lazy { detectContentBounds() }
    val contentLeft: Int get() = content[0]
    val contentTop: Int get() = content[1]
    val contentRight: Int get() = content[2]
    val contentBottom: Int get() = content[3]

    private fun detectContentBounds(): IntArray {
        var l = 0
        var t = 0
        var r = w - 1
        var b = h - 1
        fun blackRow(y: Int): Boolean {
            var dark = 0
            val samples = 64
            for (i in 0 until samples) {
                val x = (i * (w - 1) / (samples - 1)).coerceIn(0, w - 1)
                val c = px[y * w + x]
                if (((c shr 16) and 255) <= 8 && ((c shr 8) and 255) <= 8 && (c and 255) <= 8) dark++
            }
            return dark >= samples * 0.985
        }
        fun blackCol(x: Int): Boolean {
            var dark = 0
            val samples = 48
            for (i in 0 until samples) {
                val y = (i * (h - 1) / (samples - 1)).coerceIn(0, h - 1)
                val c = px[y * w + x]
                if (((c shr 16) and 255) <= 8 && ((c shr 8) and 255) <= 8 && (c and 255) <= 8) dark++
            }
            return dark >= samples * 0.985
        }
        // Chỉ tìm viền trong 25% cạnh để tránh nhầm một vùng tối nằm sâu trong game.
        val maxY = (h / 4).coerceAtLeast(1)
        val maxX = (w / 4).coerceAtLeast(1)
        while (t < maxY && blackRow(t)) t++
        while (b > h - 1 - maxY && blackRow(b)) b--
        while (l < maxX && blackCol(l)) l++
        while (r > w - 1 - maxX && blackCol(r)) r--
        if (r - l < w / 2 || b - t < h / 2) return intArrayOf(0, 0, w - 1, h - 1)
        val detectedRatio = (r - l + 1).toDouble() / (b - t + 1).toDouble()
        // Vùng game mục tiêu thường xấp xỉ 18:9 đến 22:9; bỏ kết quả crop bất thường.
        if (detectedRatio !in 1.95..2.45) return intArrayOf(0, 0, w - 1, h - 1)
        return intArrayOf(l, t, r, b)
    }

    /** Quy đổi điểm canvas chuẩn sang pixel ảnh chụp; dùng cùng phép biến đổi khi gửi thao tác chạm. */
    fun toScreen(p: Pt): Pt = Pt(mapX(p.x), mapY(p.y))

    private fun mapX(rx: Int): Int =
        (contentLeft + rx.coerceIn(0, Cfg.REF_W - 1).toDouble() * (contentRight - contentLeft) / (Cfg.REF_W - 1)).roundToInt().coerceIn(0, w - 1)

    private fun mapY(ry: Int): Int =
        (contentTop + ry.coerceIn(0, Cfg.REF_H - 1).toDouble() * (contentBottom - contentTop) / (Cfg.REF_H - 1)).roundToInt().coerceIn(0, h - 1)

    /** Điểm ảnh ARGB tại toạ độ logic chuẩn. */
    fun rgb(rx: Int, ry: Int): Int = px[mapY(ry) * w + mapX(rx)]

    /** Ảnh xám 1200x540 được chuẩn hoá theo vùng game đã phát hiện. */
    val half: IntArray by lazy { buildHalf() }

    private fun buildHalf(): IntArray {
        val out = IntArray(HW * HH)
        for (y in 0 until HH) {
            val sy0 = mapY(2 * y)
            val sy1 = mapY(2 * y + 1)
            for (x in 0 until HW) {
                val sx0 = mapX(2 * x)
                val sx1 = mapX(2 * x + 1)
                val s = gray(px[sy0 * w + sx0]) + gray(px[sy0 * w + sx1]) +
                    gray(px[sy1 * w + sx0]) + gray(px[sy1 * w + sx1])
                out[y * HW + x] = s / 4
            }
        }
        return out
    }

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
