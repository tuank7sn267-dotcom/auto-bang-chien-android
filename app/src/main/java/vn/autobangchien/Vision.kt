package vn.autobangchien

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import kotlin.math.sqrt

class Template(
    val rect: IntArray,
    val w: Int,
    val h: Int,
    val gray: IntArray,
    val mean: Double,
    val norm: Double
)

/**
 * Nhận diện màn hình bằng so khớp ảnh mẫu (tương quan chuẩn hoá, giống cv2.TM_CCOEFF_NORMED)
 * trên ảnh xám thu nhỏ một nửa, chỉ tìm quanh vị trí dự kiến để chạy nhanh trên điện thoại.
 */
class Vision(assets: AssetManager) {
    companion object {
        const val EMPTY = 0
        const val DARK = 1
        const val LIGHT = 2
    }

    private val tpl = HashMap<String, Template>()

    init {
        for ((name, rect) in Cfg.TEMPLATES) {
            tpl[name] = load(assets, name, rect)
        }
    }

    private fun load(assets: AssetManager, name: String, rect: IntArray): Template {
        val bmp = assets.open("templates/" + name + ".png").use { BitmapFactory.decodeStream(it) }
        val bw = bmp.width
        val bh = bmp.height
        val pix = IntArray(bw * bh)
        bmp.getPixels(pix, 0, bw, 0, 0, bw, bh)
        bmp.recycle()
        val tw = bw / 2
        val th = bh / 2
        val g = IntArray(tw * th)
        var sum = 0.0
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val v = (Frame.gray(pix[(2 * y) * bw + 2 * x]) + Frame.gray(pix[(2 * y) * bw + 2 * x + 1]) +
                    Frame.gray(pix[(2 * y + 1) * bw + 2 * x]) + Frame.gray(pix[(2 * y + 1) * bw + 2 * x + 1])) / 4
                g[y * tw + x] = v
                sum += v
            }
        }
        val mean = sum / (tw * th)
        var norm = 0.0
        for (v in g) {
            val d = v - mean
            norm += d * d
        }
        return Template(rect, tw, th, g, mean, norm)
    }

    /** Độ giống (-1..1) của ảnh mẫu `name` trên khung hình. */
    fun score(f: Frame, name: String): Double {
        val t = tpl[name] ?: return 0.0
        val m = Cfg.MARGIN_OVERRIDE[name] ?: Cfg.MATCH_MARGIN
        val x0 = t.rect[0]
        val y0 = t.rect[1]
        val x1 = t.rect[2]
        val y1 = t.rect[3]
        val rx0 = maxOf(0, (x0 - m) / 2)
        val rx1 = minOf(Frame.HW, (x1 + m) / 2)
        val ry0 = maxOf(0, (y0 - m) / 2)
        val ry1 = minOf(Frame.HH, (y1 + m) / 2)
        val xa = rx0
        val xb = rx1 - t.w
        val ya = ry0
        val yb = ry1 - t.h
        if (xb < xa || yb < ya) return 0.0
        var best = -2.0
        var bx = xa
        var by = ya
        var y = ya
        while (y <= yb) {
            var x = xa
            while (x <= xb) {
                val c = ncc(f, t, x, y)
                if (c > best) {
                    best = c
                    bx = x
                    by = y
                }
                x += 2
            }
            y += 2
        }
        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = bx + dx
                val yy = by + dy
                if (x >= xa && x <= xb && yy >= ya && yy <= yb) {
                    val c = ncc(f, t, x, yy)
                    if (c > best) best = c
                }
            }
        }
        return best
    }

    private fun ncc(f: Frame, t: Template, x: Int, y: Int): Double {
        val half = f.half
        val tw = t.w
        val th = t.h
        val tg = t.gray
        var cross = 0L
        var idx = 0
        for (j in 0 until th) {
            val p = (y + j) * Frame.HW + x
            var row = 0
            for (i in 0 until tw) {
                row += half[p + i] * tg[idx + i]
            }
            cross += row.toLong()
            idx += tw
        }
        val n = (tw * th).toDouble()
        val s = f.windowSum(x, y, tw, th).toDouble()
        val s2 = f.windowSum2(x, y, tw, th).toDouble()
        val num = cross.toDouble() - s * t.mean
        val varF = s2 - s * s / n
        if (varF <= 1e-6) return 0.0
        val den = sqrt(varF * t.norm)
        if (den <= 1e-6) return 0.0
        return num / den
    }

    /** Xác định đang ở màn hình nào (thứ tự ưu tiên giống bản máy tính). */
    fun identify(f: Frame): String {
        val memo = HashMap<String, Boolean>()
        fun ok(n: String): Boolean {
            val c = memo[n]
            if (c != null) return c
            val r = score(f, n) >= Cfg.MATCH_THRESHOLD
            memo[n] = r
            return r
        }
        if (ok("result_mainmenu")) return Cfg.ST_RESULT
        if (ok("battle_pause")) return Cfg.ST_BATTLE
        if (ok("dialog_close") || ok("dialog_attack")) return Cfg.ST_DIALOG
        if (ok("main_attack") && !ok("grid_clock")) return Cfg.ST_MAIN
        if (ok("game_chon")) return Cfg.ST_LOBBY
        if (ok("grid_clock") || ok("grid_label")) return Cfg.ST_GRID
        if (ok("guild_play")) return Cfg.ST_GUILD_HOME
        if (ok("town_lapnhom") || ok("town_banghoi")) return Cfg.ST_TOWN
        if (ok("back_arrow")) return Cfg.ST_BACKABLE
        return Cfg.ST_UNKNOWN
    }

    /** Tỉ lệ lấp đầy của thanh tiến trình (0..1), tìm điểm vàng xa nhất. */
    fun barRatio(f: Frame, bar: IntArray): Double {
        val x0 = bar[0]
        val y0 = bar[1]
        val x1 = bar[2]
        val y1 = bar[3]
        var best = -1
        val ys = intArrayOf(y0 + 4, y1 - 4)
        for (y in ys) {
            for (x in x0..x1) {
                val c = f.rgb(x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                if (r > 180 && g > 130 && b < 140 && r - b > 80) {
                    if (x - x0 > best) best = x - x0
                }
            }
        }
        return if (best < 0) 0.0 else (best + 1).toDouble() / (x1 - x0 + 1).toDouble()
    }

    /** Phân loại 30 ô nhà: EMPTY / DARK (đang đánh, đã clear) / LIGHT (đánh được). Chỉ số 1..30. */
    fun scanCells(f: Frame): IntArray {
        val out = IntArray(31)
        for (i in 1..30) {
            val q = Cfg.cellRect(i)
            var dark = 0
            var white = 0
            var n = 0
            var y = q[1] + 6
            while (y < q[3] - 6) {
                var x = q[0] + 6
                while (x < q[2] - 6) {
                    val c = f.rgb(x, y)
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    val mx = maxOf(r, maxOf(g, b))
                    val mn = minOf(r, minOf(g, b))
                    if (mx < 75) dark++
                    if (mn > 200) white++
                    n++
                    x += 2
                }
                y += 2
            }
            val wr = white.toDouble() / maxOf(1, n)
            val dr = dark.toDouble() / maxOf(1, n)
            if (wr >= Cfg.CELL_LIGHT_RATIO) {
                out[i] = LIGHT
            } else if (dr >= Cfg.CELL_DARK_RATIO) {
                out[i] = DARK
            }
        }
        return out
    }

    /** Deck 1,2,3 còn dùng được không (hàng sáng = còn dùng, hàng tối = đã hiện Thắng/Thua). */
    fun deckAvailable(f: Frame): BooleanArray {
        val out = BooleanArray(3)
        for (i in 0 until 3) {
            val y = Cfg.DECK_ROW_Y[i]
            var sum = 0L
            var n = 0
            var yy = y - 18
            while (yy < y + 18) {
                var xx = Cfg.DECK_SAMPLE_X0
                while (xx < Cfg.DECK_SAMPLE_X1) {
                    sum += Frame.gray(f.rgb(xx, yy))
                    n++
                    xx += 3
                }
                yy += 3
            }
            out[i] = (sum.toDouble() / maxOf(1, n)) >= Cfg.DECK_LIGHT_VALUE
        }
        return out
    }

    /** Deck đang được chọn (có dấu tích đỏ): 0, 1, 2 hoặc -1 nếu chưa chọn deck nào. */
    fun selectedDeck(f: Frame, avail: BooleanArray?): Int {
        for (i in 0 until 3) {
            if (avail != null && !avail[i]) continue
            val y = Cfg.DECK_ROW_Y[i]
            var red = 0
            for (yy in (y - 26) until (y + 26)) {
                for (xx in Cfg.DECK_CHECK_X0 until Cfg.DECK_CHECK_X1) {
                    val c = f.rgb(xx, yy)
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    if (r > 170 && g < 90 && b < 90) red++
                }
            }
            if (red >= Cfg.DECK_CHECK_MIN) return i
        }
        return -1
    }
}
