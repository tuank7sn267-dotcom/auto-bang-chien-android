package vn.autobangchien

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

class Template(
    val rect: IntArray,
    val w: Int,
    val h: Int,
    val gray: IntArray,
    val mean: Double,
    val norm: Double
)

data class TemplateMatch(val center: Pt, val scaleX: Double, val scaleY: Double, val score: Double)

/**
 * Nhận diện màn hình bằng so khớp ảnh mẫu (tương quan chuẩn hoá, giống cv2.TM_CCOEFF_NORMED)
 * trên ảnh xám thu nhỏ một nửa: thử vùng dự kiến trước, sau đó dùng OpenCV tìm toàn vùng
 * với nhiều tỷ lệ X/Y nếu giao diện đã dịch chuyển hoặc thay đổi kích thước.
 */
class Vision(assets: AssetManager) {
    companion object {
        const val EMPTY = 0
        const val DARK = 1
        const val LIGHT = 2
    }

    private val tpl = HashMap<String, Template>()
    private val templateMats = HashMap<String, Mat>()
    private var openCvReady = false
    private var cachedFrame: Frame? = null
    private var cachedFrameMat: Mat? = null
    private val matchCache = HashMap<String, TemplateMatch?>()

    init {
        try {
            openCvReady = OpenCVLoader.initLocal()
            if (openCvReady) BotLog.i("OpenCV đã sẵn sàng: tìm ảnh mẫu đa tỷ lệ.")
            else BotLog.e("OpenCV không khởi tạo được; dùng bộ nhận diện dự phòng.")
        } catch (e: Exception) {
            BotLog.e("Không khởi tạo được OpenCV: " + e.message)
        }
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

    private fun matForFrame(f: Frame): Mat {
        if (cachedFrame === f && cachedFrameMat != null) return cachedFrameMat!!
        cachedFrameMat?.release()
        cachedFrame = f
        matchCache.clear()
        val bytes = ByteArray(Frame.HW * Frame.HH)
        val pixels = f.half
        for (i in pixels.indices) bytes[i] = pixels[i].toByte()
        val mat = Mat(Frame.HH, Frame.HW, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        cachedFrameMat = mat
        return mat
    }

    private fun templateMat(name: String, t: Template): Mat {
        return templateMats.getOrPut(name) {
            val bytes = ByteArray(t.gray.size)
            for (i in t.gray.indices) bytes[i] = t.gray[i].toByte()
            Mat(t.h, t.w, CvType.CV_8UC1).also { it.put(0, 0, bytes) }
        }
    }

    /** Tìm ảnh mẫu trên toàn vùng game, có thử nhiều tỷ lệ để thích nghi UI co giãn. */
    fun locate(f: Frame, name: String): TemplateMatch? {
        if (!openCvReady) return null
        if (cachedFrame !== f) {
            matForFrame(f)
        }
        if (matchCache.containsKey(name)) return matchCache[name]
        val t = tpl[name] ?: return null
        val src = cachedFrameMat ?: return null
        val base = templateMat(name, t)
        var bestScore = -2.0
        var bestX = 0
        var bestY = 0
        var bestScaleX = 1.0
        var bestScaleY = 1.0
        // Thử cả co giãn không đồng nhất: nhiều game thay đổi bố cục theo chiều rộng/cao riêng biệt.
        val scales = arrayOf(
            0.85 to 1.0, 0.90 to 1.0, 1.0 to 1.0, 1.10 to 1.0, 1.15 to 1.0, 1.25 to 1.0,
            1.0 to 0.90, 1.0 to 1.10, 0.90 to 0.90, 1.10 to 1.10, 1.25 to 1.10, 1.10 to 0.90
        )
        for ((scaleX, scaleY) in scales) {
            val tw = (t.w * scaleX).toInt().coerceAtLeast(8)
            val th = (t.h * scaleY).toInt().coerceAtLeast(8)
            if (tw >= Frame.HW || th >= Frame.HH) continue
            val scaled = Mat()
            val result = Mat()
            try {
                Imgproc.resize(base, scaled, Size(tw.toDouble(), th.toDouble()), 0.0, 0.0,
                    if (scaleX < 1.0 || scaleY < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
                Imgproc.matchTemplate(src, scaled, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal > bestScore) {
                    bestScore = mm.maxVal
                    bestX = mm.maxLoc.x.toInt()
                    bestY = mm.maxLoc.y.toInt()
                    bestScaleX = scaleX
                    bestScaleY = scaleY
                }
            } finally {
                scaled.release()
                result.release()
            }
        }
        val match = if (bestScore > 0.0) TemplateMatch(
            Pt((bestX + t.w * bestScaleX / 2.0).toInt() * 2,
                (bestY + t.h * bestScaleY / 2.0).toInt() * 2), bestScaleX, bestScaleY, bestScore
        ) else null
        matchCache[name] = match
        return match
    }

    /** Điều chỉnh điểm theo điểm neo tìm được trên giao diện hiện tại. */
    fun adjustPoint(f: Frame, anchorName: String, point: Pt): Pt {
        val t = tpl[anchorName] ?: return point
        val m = locate(f, anchorName) ?: return point
        if (m.score < Cfg.MATCH_THRESHOLD) return point
        val anchor = Pt((t.rect[0] + t.rect[2]) / 2, (t.rect[1] + t.rect[3]) / 2)
        return Pt(m.center.x + ((point.x - anchor.x) * m.scaleX).toInt(),
            m.center.y + ((point.y - anchor.y) * m.scaleY).toInt())
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
        if (xb < xa || yb < ya) return locate(f, name)?.score ?: 0.0
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
        if (best < Cfg.MATCH_THRESHOLD) {
            val global = locate(f, name)?.score ?: -1.0
            if (global > best) best = global
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

    fun adjustPointByAnchor(f: Frame, anchorName: String, point: Pt): Pt = adjustPoint(f, anchorName, point)

    fun cellCenter(f: Frame, index: Int): Pt = adjustPoint(f, "grid_label", Cfg.cellCenter(index))

    /** Tỉ lệ lấp đầy của thanh tiến trình (0..1), tìm điểm vàng xa nhất. */
    fun barRatio(f: Frame, bar: IntArray, anchorName: String? = null): Double {
        val q = if (anchorName != null) {
            val a = tpl[anchorName]
            val m = locate(f, anchorName)?.takeIf { it.score >= Cfg.MATCH_THRESHOLD }
            if (a != null && m != null) {
                val cx = (a.rect[0] + a.rect[2]) / 2
                val cy = (a.rect[1] + a.rect[3]) / 2
                intArrayOf(
                    m.center.x + ((bar[0] - cx) * m.scaleX).toInt(),
                    m.center.y + ((bar[1] - cy) * m.scaleY).toInt(),
                    m.center.x + ((bar[2] - cx) * m.scaleX).toInt(),
                    m.center.y + ((bar[3] - cy) * m.scaleY).toInt()
                )
            } else bar
        } else bar
        val x0 = q[0].coerceIn(0, Cfg.REF_W - 1)
        val y0 = q[1].coerceIn(0, Cfg.REF_H - 1)
        val x1 = q[2].coerceIn(x0, Cfg.REF_W - 1)
        val y1 = q[3].coerceIn(y0, Cfg.REF_H - 1)
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
        val anchor = tpl["grid_label"]
        val match = locate(f, "grid_label")?.takeIf { it.score >= Cfg.MATCH_THRESHOLD }
        val baseAnchor = if (anchor != null) Pt((anchor.rect[0] + anchor.rect[2]) / 2, (anchor.rect[1] + anchor.rect[3]) / 2) else Pt(0, 0)
        val scaleX = match?.scaleX ?: 1.0
        val scaleY = match?.scaleY ?: 1.0
        val dx = (match?.center?.x ?: baseAnchor.x) - baseAnchor.x
        val dy = (match?.center?.y ?: baseAnchor.y) - baseAnchor.y
        for (i in 1..30) {
            val raw = Cfg.cellRect(i)
            val q = intArrayOf(
                (match?.center?.x?.plus(((raw[0] - baseAnchor.x) * scaleX).toInt()) ?: raw[0] + dx),
                (match?.center?.y?.plus(((raw[1] - baseAnchor.y) * scaleY).toInt()) ?: raw[1] + dy),
                (match?.center?.x?.plus(((raw[2] - baseAnchor.x) * scaleX).toInt()) ?: raw[2] + dx),
                (match?.center?.y?.plus(((raw[3] - baseAnchor.y) * scaleY).toInt()) ?: raw[3] + dy)
            )
            q[0] = q[0].coerceIn(0, Cfg.REF_W - 1)
            q[1] = q[1].coerceIn(0, Cfg.REF_H - 1)
            q[2] = q[2].coerceIn(q[0] + 1, Cfg.REF_W)
            q[3] = q[3].coerceIn(q[1] + 1, Cfg.REF_H)
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
        val base = tpl["dialog_close"]
        val m = locate(f, "dialog_close")?.takeIf { it.score >= Cfg.MATCH_THRESHOLD }
        val ax = if (base != null) (base.rect[0] + base.rect[2]) / 2 else 0
        val ay = if (base != null) (base.rect[1] + base.rect[3]) / 2 else 0
        val scaleX = m?.scaleX ?: 1.0
        val scaleY = m?.scaleY ?: 1.0
        val dx = (m?.center?.x ?: ax) - ax
        val dy = (m?.center?.y ?: ay) - ay
        for (i in 0 until 3) {
            val y = (m?.center?.y?.plus(((Cfg.DECK_ROW_Y[i] - ay) * scaleY).toInt()) ?: (Cfg.DECK_ROW_Y[i] + dy))
            var sum = 0L
            var n = 0
            val halfHeight = (18 * scaleY).toInt().coerceAtLeast(6)
            var yy = y - halfHeight
            while (yy < y + halfHeight) {
                var xx = (m?.center?.x?.plus(((Cfg.DECK_SAMPLE_X0 - ax) * scaleX).toInt()) ?: (Cfg.DECK_SAMPLE_X0 + dx))
                val sampleX1 = (m?.center?.x?.plus(((Cfg.DECK_SAMPLE_X1 - ax) * scaleX).toInt()) ?: (Cfg.DECK_SAMPLE_X1 + dx))
                while (xx < sampleX1) {
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
        val base = tpl["dialog_close"]
        val m = locate(f, "dialog_close")?.takeIf { it.score >= Cfg.MATCH_THRESHOLD }
        val ax = if (base != null) (base.rect[0] + base.rect[2]) / 2 else 0
        val ay = if (base != null) (base.rect[1] + base.rect[3]) / 2 else 0
        val scaleX = m?.scaleX ?: 1.0
        val scaleY = m?.scaleY ?: 1.0
        val dx = (m?.center?.x ?: ax) - ax
        val dy = (m?.center?.y ?: ay) - ay
        for (i in 0 until 3) {
            if (avail != null && !avail[i]) continue
            val y = (m?.center?.y?.plus(((Cfg.DECK_ROW_Y[i] - ay) * scaleY).toInt()) ?: (Cfg.DECK_ROW_Y[i] + dy))
            var red = 0
            val checkX0 = (m?.center?.x?.plus(((Cfg.DECK_CHECK_X0 - ax) * scaleX).toInt()) ?: (Cfg.DECK_CHECK_X0 + dx))
            val checkX1 = (m?.center?.x?.plus(((Cfg.DECK_CHECK_X1 - ax) * scaleX).toInt()) ?: (Cfg.DECK_CHECK_X1 + dx))
            val halfHeight = (26 * scaleY).toInt().coerceAtLeast(8)
            for (yy in (y - halfHeight) until (y + halfHeight)) {
                for (xx in checkX0 until checkX1) {
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
