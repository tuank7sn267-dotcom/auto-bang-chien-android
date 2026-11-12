package vn.autobangchien

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.sqrt

/** Ảnh mẫu dạng xám cùng thống kê dùng cho tương quan chuẩn hoá. */
class Tpl(val w: Int, val h: Int, val d: IntArray) {
    val n: Double = (w * h).toDouble()
    val mean: Double
    val norm: Double

    init {
        var s = 0.0
        for (v in d) s += v
        mean = s / n
        var q = 0.0
        for (v in d) {
            val e = v - mean
            q += e * e
        }
        norm = q
    }
}

class Template(val rect: IntArray, val half: Tpl, val quarter: Tpl)

/** Kết quả dò: điểm giống và độ lệch (dx, dy) so với vị trí chuẩn, tính theo toạ độ chuẩn. */
class Loc(val score: Double, val dx: Int, val dy: Int)

/**
 * Bố cục đã học của thiết bị này: mỗi ảnh mẫu đã tìm thấy lệch bao nhiêu so với máy chuẩn,
 * và tỉ lệ giao diện đã được xác nhận hay chưa.
 */
class Layout {
    val mem = HashMap<String, IntArray>()
    @Volatile var mul: Double = 1.0
    @Volatile var validated: Boolean = false
}

/**
 * Nhận diện màn hình bằng so khớp ảnh mẫu (tương quan chuẩn hoá, giống cv2.TM_CCOEFF_NORMED).
 *
 * Để chạy được trên mọi màn hình: tìm quanh vị trí đã nhớ trước (rất nhanh); nếu không thấy thì
 * dò theo DẢI NGANG trên toàn bề ngang (giao diện neo trái/giữa/phải nên chỉ lệch theo chiều ngang),
 * rồi nhớ lại vị trí thật. Mọi điểm bấm và vùng đo màu đều dịch theo vị trí thật đó.
 */
class Vision(assets: AssetManager) {
    companion object {
        const val EMPTY = 0
        const val DARK = 1
        const val LIGHT = 2
    }

    @Volatile var layout = Layout()
    private val tpl = HashMap<String, Template>()

    private class Hit(val score: Double, val x: Int, val y: Int)

    init {
        for ((name, rect) in Cfg.TEMPLATES) {
            tpl[name] = load(assets, name, rect)
        }
    }

    private fun shrink(t: Tpl): Tpl {
        val w = t.w / 2
        val h = t.h / 2
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (2 * y) * t.w + 2 * x
                out[y * w + x] = (t.d[i] + t.d[i + 1] + t.d[i + t.w] + t.d[i + t.w + 1]) / 4
            }
        }
        return Tpl(w, h, out)
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
        for (y in 0 until th) {
            for (x in 0 until tw) {
                g[y * tw + x] = (Frame.gray(pix[(2 * y) * bw + 2 * x]) + Frame.gray(pix[(2 * y) * bw + 2 * x + 1]) +
                    Frame.gray(pix[(2 * y + 1) * bw + 2 * x]) + Frame.gray(pix[(2 * y + 1) * bw + 2 * x + 1])) / 4
            }
        }
        val half = Tpl(tw, th, blur3(g, tw, th))
        return Template(rect, half, shrink(half))
    }

    // ------------------------------------------------------------------ so khớp
    private fun ncc(p: Plane, t: Tpl, x: Int, y: Int): Double {
        val d = p.d
        val pw = p.w
        val tw = t.w
        val th = t.h
        val tg = t.d
        var cross = 0L
        var idx = 0
        for (j in 0 until th) {
            val base = (y + j) * pw + x
            var row = 0
            for (i in 0 until tw) {
                row += d[base + i] * tg[idx + i]
            }
            cross += row.toLong()
            idx += tw
        }
        val s = p.sum(x, y, tw, th).toDouble()
        val s2 = p.sum2(x, y, tw, th).toDouble()
        val num = cross.toDouble() - s * t.mean
        val varF = s2 - s * s / t.n
        if (varF <= 1e-6) return 0.0
        val den = sqrt(varF * t.norm)
        if (den <= 1e-6) return 0.0
        return num / den
    }

    /** Tìm quanh (ex, ey) với biên mx, my: quét thô theo `step` rồi tinh chỉnh quanh điểm tốt nhất. */
    private fun localSearch(p: Plane, t: Tpl, ex: Int, ey: Int, mx: Int, my: Int, step: Int): Hit {
        val xa = maxOf(0, ex - mx)
        val xb = minOf(p.w - t.w, ex + mx)
        val ya = maxOf(0, ey - my)
        val yb = minOf(p.h - t.h, ey + my)
        if (xb < xa || yb < ya) return Hit(-2.0, 0, 0)
        var best = -2.0
        var bx = xa
        var by = ya
        var y = ya
        while (y <= yb) {
            var x = xa
            while (x <= xb) {
                val c = ncc(p, t, x, y)
                if (c > best) {
                    best = c
                    bx = x
                    by = y
                }
                x += step
            }
            y += step
        }
        if (step > 1) {
            for (dy in -(step - 1)..(step - 1)) {
                for (dx in -(step - 1)..(step - 1)) {
                    val x = bx + dx
                    val yy = by + dy
                    if (x >= xa && x <= xb && yy >= ya && yy <= yb) {
                        val c = ncc(p, t, x, yy)
                        if (c > best) best = c
                    }
                }
            }
        }
        return Hit(best, bx, by)
    }

    private fun remember(name: String, dx: Int, dy: Int) {
        val old = layout.mem[name]
        val moved = if (old == null) (abs(dx) > 3 || abs(dy) > 3) else (abs(old[0] - dx) > 3 || abs(old[1] - dy) > 3)
        if (moved) BotLog.i("Dò được vị trí '" + name + "': lệch (" + dx + "," + dy + ")")
        layout.mem[name] = intArrayOf(dx, dy)
    }

    /**
     * Tìm ảnh mẫu `name`. band=false: chỉ tìm quanh vị trí đã nhớ. band=true: nếu không thấy thì dò
     * theo dải ngang trên toàn bề ngang (thô ở 1/4 rồi tinh chỉnh ở 1/2) và nhớ lại vị trí.
     */
    fun locate(f: Frame, name: String, band: Boolean): Loc {
        val t = tpl[name] ?: return Loc(0.0, 0, 0)
        val x0 = t.rect[0]
        val y0 = t.rect[1]
        val mem = layout.mem[name]
        val dx0 = if (mem != null) mem[0] else 0
        val dy0 = if (mem != null) mem[1] else 0
        val ex = (x0 + dx0) / 2
        val ey = (y0 + dy0) / 2
        val mgn = Cfg.MARGIN_OVERRIDE[name]
        val m = (if (mgn != null) mgn else Cfg.MATCH_MARGIN) / 2
        val h1 = localSearch(f.half, t.half, ex, ey, m, m, 2)
        if (h1.score >= Cfg.MATCH_THRESHOLD) {
            val loc = Loc(h1.score, h1.x * 2 - x0, h1.y * 2 - y0)
            remember(name, loc.dx, loc.dy)
            return loc
        }
        if (!band) return Loc(h1.score, h1.x * 2 - x0, h1.y * 2 - y0)

        val qt = t.quarter
        val qp = f.quarter
        val qm = (if (mgn != null) mgn else Cfg.BAND_MARGIN_Y) / 4
        val qey = ey / 2
        val ya = maxOf(0, qey - qm)
        val yb = minOf(qp.h - qt.h, qey + qm)
        val xb = qp.w - qt.w
        if (yb < ya || xb < 0) return Loc(h1.score, h1.x * 2 - x0, h1.y * 2 - y0)
        var best = -2.0
        var bx = 0
        var by = ya
        for (y in ya..yb) {
            for (x in 0..xb) {
                val c = ncc(qp, qt, x, y)
                if (c > best) {
                    best = c
                    bx = x
                    by = y
                }
            }
        }
        val h2 = localSearch(f.half, t.half, bx * 2, by * 2, 3, 3, 1)
        val loc = Loc(h2.score, h2.x * 2 - x0, h2.y * 2 - y0)
        if (h2.score >= Cfg.MATCH_THRESHOLD) remember(name, loc.dx, loc.dy)
        return loc
    }

    /** Điểm giống của ảnh mẫu khi tìm quanh vị trí chuẩn dịch đi s (không đụng tới bộ nhớ). */
    fun scoreAt(f: Frame, name: String, s: IntArray): Double {
        val t = tpl[name] ?: return 0.0
        val mgn = Cfg.MARGIN_OVERRIDE[name]
        val m = (if (mgn != null) mgn else Cfg.MATCH_MARGIN) / 2
        return localSearch(f.half, t.half, (t.rect[0] + s[0]) / 2, (t.rect[1] + s[1]) / 2, m, m, 2).score
    }

    /** Độ dịch (dx, dy) đã nhớ của ảnh mẫu; chưa có thì (0, 0). */
    fun anchor(name: String): IntArray {
        val m = layout.mem[name]
        return m ?: intArrayOf(0, 0)
    }

    /** Độ dịch của ảnh mẫu: dùng giá trị đã nhớ, chưa có thì dò theo dải ngang, không thấy thì dùng `fallback`. */
    fun shiftFor(f: Frame, name: String, fallback: IntArray?): IntArray {
        val m = layout.mem[name]
        if (m != null) return m
        val loc = locate(f, name, true)
        if (loc.score >= Cfg.ANCHOR_THRESHOLD) {
            remember(name, loc.dx, loc.dy)
            return intArrayOf(loc.dx, loc.dy)
        }
        return fallback ?: intArrayOf(0, 0)
    }

    /** Xác định đang ở màn hình nào (thứ tự ưu tiên giống bản máy tính). */
    fun identify(f: Frame, band: Boolean): String {
        val memo = HashMap<String, Boolean>()
        fun ok(n: String): Boolean {
            val c = memo[n]
            if (c != null) return c
            val r = locate(f, n, band).score >= Cfg.MATCH_THRESHOLD
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

    // ------------------------------------------------------------------ 5 ô nhân vật
    /** Dò viên ngọc dưới mỗi ô nhân vật; trả về tâm các ô (toạ độ chuẩn). Rỗng nếu không thấy. */
    fun findCards(f: Frame): List<Pt> {
        val t = tpl["card_gem"] ?: return ArrayList<Pt>()
        val g = t.half
        val hp = f.half
        val y0 = t.rect[1]
        val ya = maxOf(0, (y0 - 30) / 2)
        val yb = minOf(hp.h - g.h, (y0 + 30) / 2)
        val xb = hp.w - g.w
        val out = ArrayList<Pt>()
        if (yb < ya || xb < 0) return out
        val rows = yb - ya + 1
        val cols = xb + 1
        val sc = DoubleArray(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                sc[r * cols + c] = ncc(hp, g, c, ya + r)
            }
        }
        // chọn các đỉnh cao nhất, mỗi đỉnh loại bỏ vùng ±50 xung quanh
        val pk = ArrayList<IntArray>()
        for (k in 0 until 12) {
            var bi = -1
            var bs = Cfg.GEM_THRESHOLD
            for (i in sc.indices) {
                if (sc[i] >= bs) {
                    bs = sc[i]
                    bi = i
                }
            }
            if (bi < 0) break
            val bc = bi % cols
            val br = bi / cols
            pk.add(intArrayOf(bc * 2, (ya + br) * 2))
            for (r in 0 until rows) {
                for (c in maxOf(0, bc - 50)..minOf(cols - 1, bc + 50)) {
                    sc[r * cols + c] = -1.0
                }
            }
        }
        pk.sortBy { it[0] }
        // lấy chuỗi dài nhất các viên ngọc cách đều ~279px (loại biểu tượng ngọc ở nút nâng cấp pha lê)
        var best = ArrayList<IntArray>()
        for (i in pk.indices) {
            val chain = ArrayList<IntArray>()
            chain.add(pk[i])
            for (j in (i + 1) until pk.size) {
                val gap = pk[j][0] - chain[chain.size - 1][0]
                if (abs(gap - Cfg.GEM_SPACING) <= Cfg.GEM_SPACING_TOL) chain.add(pk[j])
            }
            if (chain.size > best.size) best = chain
        }
        if (best.size < 2) return out
        for (p in best) {
            out.add(Pt(p[0] + Cfg.CARD_DX, p[1] + Cfg.CARD_DY))
        }
        return out
    }

    // ------------------------------------------------------------------ đo màu (đều nhận độ dịch s)
    /** Tỉ lệ lấp đầy của thanh tiến trình (0..1), tìm điểm vàng xa nhất. */
    fun barRatio(f: Frame, bar: IntArray, s: IntArray): Double {
        val x0 = bar[0]
        val y0 = bar[1]
        val x1 = bar[2]
        val y1 = bar[3]
        var best = -1
        val ys = intArrayOf(y0 + 4, y1 - 4)
        for (y in ys) {
            for (x in x0..x1) {
                val c = f.rgb(x + s[0], y + s[1])
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
    fun scanCells(f: Frame, s: IntArray): IntArray {
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
                    val c = f.rgb(x + s[0], y + s[1])
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
    fun deckAvailable(f: Frame, s: IntArray): BooleanArray {
        val out = BooleanArray(3)
        for (i in 0 until 3) {
            val y = Cfg.DECK_ROW_Y[i]
            var sum = 0L
            var n = 0
            var yy = y - 18
            while (yy < y + 18) {
                var xx = Cfg.DECK_SAMPLE_X0
                while (xx < Cfg.DECK_SAMPLE_X1) {
                    sum += Frame.gray(f.rgb(xx + s[0], yy + s[1]))
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
    fun selectedDeck(f: Frame, avail: BooleanArray?, s: IntArray): Int {
        for (i in 0 until 3) {
            if (avail != null && !avail[i]) continue
            val y = Cfg.DECK_ROW_Y[i]
            var red = 0
            for (yy in (y - 26) until (y + 26)) {
                for (xx in Cfg.DECK_CHECK_X0 until Cfg.DECK_CHECK_X1) {
                    val c = f.rgb(xx + s[0], yy + s[1])
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
