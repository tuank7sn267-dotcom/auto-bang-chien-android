package vn.autobangchien

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** OpenCV implementation of the same template matching and pixel rules used by the latest PC tool. */
class BangChienVision(assets: AssetManager) : AutoCloseable {
    companion object {
        const val REF_W = 2400
        const val REF_H = 1080
        const val MATCH_THRESHOLD = 0.75
        const val MATCH_MARGIN = 90
        const val CELL_DARK_RATIO = 0.35
        const val CELL_LIGHT_RATIO = 0.30
        const val DECK_LIGHT_VALUE = 150.0
        const val DECK_CHECK_MIN = 60
        private val RECTS = linkedMapOf(
            "game_chon" to intArrayOf(237, 31, 579, 95),
            "main_attack" to intArrayOf(1347, 967, 1668, 1028),
            "main_newguild" to intArrayOf(1798, 967, 2150, 1028),
            "grid_clock" to intArrayOf(1892, 40, 2120, 86),
            "grid_label" to intArrayOf(992, 135, 1133, 171),
            "dialog_close" to intArrayOf(834, 899, 1209, 973),
            "dialog_attack" to intArrayOf(1316, 895, 1668, 963),
            "grid_defeated" to intArrayOf(895, 566, 1612, 610),
            "battle_pause" to intArrayOf(2281, 31, 2370, 104),
            "result_mainmenu" to intArrayOf(1140, 964, 1370, 1028)
        )
        private val MARGIN_OVERRIDE = mapOf("main_attack" to 25, "dialog_attack" to 25, "grid_defeated" to 40)
        val DECK_ROW_Y = intArrayOf(505, 633, 762)
        private const val DECK_SAMPLE_X0 = 827
        private const val DECK_SAMPLE_X1 = 1071
        private const val DECK_CHECK_X0 = 735
        private const val DECK_CHECK_X1 = 815
    }

    enum class State { LOBBY_SELECT, MAIN, GRID, DIALOG, BATTLE, RESULT, UNKNOWN }

    private data class Template(val name: String, val gray: Mat)
    private val templates = LinkedHashMap<String, Template>()

    init {
        check(OpenCVLoader.initLocal()) { "Không khởi tạo được OpenCV native library" }
        for ((name, _) in RECTS) {
            val bitmap = assets.open("templates/$name.png").use { BitmapFactory.decodeStream(it) }
                ?: error("Thiếu ảnh mẫu templates/$name.png")
            val rgba = Mat()
            val gray = Mat()
            val half = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, half, Size((gray.cols() / 2).toDouble(), (gray.rows() / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            templates[name] = Template(name, half)
            rgba.release(); gray.release(); bitmap.recycle()
        }
    }

    inner class Frame internal constructor(
        val bitmap: Bitmap, private val grayHalf: Mat,
        val displayWidth: Int, val displayHeight: Int
    ) : AutoCloseable {
        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height
        private val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

        fun score(name: String): Double {
            val rect = RECTS[name] ?: return 0.0
            val template = templates[name]?.gray ?: return 0.0
            val margin = MARGIN_OVERRIDE[name] ?: MATCH_MARGIN
            val x0 = max(0, (rect[0] - margin) / 2)
            val y0 = max(0, (rect[1] - margin) / 2)
            val x1 = min(grayHalf.cols(), (rect[2] + margin) / 2)
            val y1 = min(grayHalf.rows(), (rect[3] + margin) / 2)
            if (x1 <= x0 || y1 <= y0 || x1 - x0 < template.cols() || y1 - y0 < template.rows()) return 0.0
            val roi = grayHalf.submat(Rect(x0, y0, x1 - x0, y1 - y0))
            val result = Mat()
            return try {
                Imgproc.matchTemplate(roi, template, result, Imgproc.TM_CCOEFF_NORMED)
                Core.minMaxLoc(result).maxVal.let { if (it.isFinite()) it else 0.0 }
            } finally {
                roi.release(); result.release()
            }
        }

        fun identify(): Pair<State, Map<String, Double>> {
            val scores = RECTS.keys.associateWith { score(it) }
            fun ok(n: String) = (scores[n] ?: 0.0) >= MATCH_THRESHOLD
            val state = when {
                ok("result_mainmenu") -> State.RESULT
                ok("battle_pause") -> State.BATTLE
                ok("dialog_close") || ok("dialog_attack") -> State.DIALOG
                ok("main_attack") && !ok("grid_clock") -> State.MAIN
                ok("game_chon") -> State.LOBBY_SELECT
                ok("grid_clock") || ok("grid_label") -> State.GRID
                else -> State.UNKNOWN
            }
            return state to scores
        }

        fun barRatio(rect: IntArray): Double {
            var best = -1
            for (y in intArrayOf(rect[1] + 4, rect[3] - 4)) {
                for (x in rect[0]..rect[2]) {
                    val c = pixel(x, y)
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    if (r > 180 && g > 130 && b < 140 && r - b > 80) best = max(best, x - rect[0])
                }
            }
            return if (best < 0) 0.0 else (best + 1).toDouble() / (rect[2] - rect[0] + 1)
        }

        fun gridDone(): Boolean = barRatio(intArrayOf(1146, 143, 1468, 162)) >= 0.98 || score("grid_defeated") >= MATCH_THRESHOLD

        /** Returns 1..30 => light/dark. Empty cells are omitted. */
        fun scanCells(): Map<Int, String> {
            val out = LinkedHashMap<Int, String>()
            for (i in 1..30) {
                val row = (i - 1) / 5
                val col = (i - 1) % 5
                val top = (210 + 130.4 * row).toInt()
                val left = ((if (row % 2 == 0) 471 else 298) + 349.8 * col).toInt()
                var dark = 0; var white = 0; var count = 0
                for (y in top + 6 until top + 113 step 3) for (x in left + 6 until left + 332 step 3) {
                    if (x !in 0 until REF_W || y !in 0 until REF_H) continue
                    val c = pixel(x, y)
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                    if (mx < 75) dark++
                    if (mn > 200) white++
                    count++
                }
                val whiteRatio = white.toDouble() / max(1, count)
                val darkRatio = dark.toDouble() / max(1, count)
                if (whiteRatio >= CELL_LIGHT_RATIO) out[i] = "light"
                else if (darkRatio >= CELL_DARK_RATIO) out[i] = "dark"
            }
            return out
        }

        fun deckAvailable(): List<Boolean> = DECK_ROW_Y.map { y ->
            var sum = 0.0; var count = 0
            for (yy in y - 18 until y + 18 step 2) for (x in DECK_SAMPLE_X0 until DECK_SAMPLE_X1 step 2) {
                val c = pixel(x, yy)
                sum += Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114
                count++
            }
            sum.toDouble() / max(1, count) >= DECK_LIGHT_VALUE
        }

        fun selectedDeck(available: List<Boolean>? = null): Int? {
            for (i in DECK_ROW_Y.indices) {
                if (available != null && !available[i]) continue
                val y = DECK_ROW_Y[i]
                var red = 0
                for (yy in y - 26 until y + 26) for (x in DECK_CHECK_X0 until DECK_CHECK_X1) {
                    val c = pixel(x, yy)
                    if (Color.red(c) > 170 && Color.green(c) < 90 && Color.blue(c) < 90) red++
                }
                if (red >= DECK_CHECK_MIN) return i
            }
            return null
        }

        fun smallSignature(): IntArray {
            val small = Mat()
            return try {
                Imgproc.resize(grayHalf, small, Size(160.0, 72.0), 0.0, 0.0, Imgproc.INTER_AREA)
                val bytes = ByteArray(160 * 72)
                small.get(0, 0, bytes)
                IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
            } finally {
                small.release()
            }
        }

        private fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

        override fun close() {
            grayHalf.release()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun prepare(input: Bitmap): Frame {
        val displayWidth = input.width
        val displayHeight = input.height
        val ref = if (input.width != REF_W || input.height != REF_H) {
            Bitmap.createScaledBitmap(input, REF_W, REF_H, true).also { if (!input.isRecycled) input.recycle() }
        } else input
        val rgba = Mat(); val gray = Mat(); val half = Mat()
        Utils.bitmapToMat(ref, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.resize(gray, half, Size((REF_W / 2).toDouble(), (REF_H / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        rgba.release(); gray.release()
        return Frame(ref, half, displayWidth, displayHeight)
    }

    override fun close() { templates.values.forEach { it.gray.release() }; templates.clear() }
}
