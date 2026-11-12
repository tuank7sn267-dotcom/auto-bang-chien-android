package vn.autobangchien

/** Điểm bấm theo canvas logic 2400x1080; Frame tự ánh xạ sang vùng game thực tế. */
class Pt(val x: Int, val y: Int)

/** Toàn bộ cấu hình, giống bản máy tính (auto_bang_chien.py). */
object Cfg {
    const val REF_W = 2400
    const val REF_H = 1080

    const val ST_LOBBY = "LOBBY_SELECT"
    const val ST_MAIN = "MAIN"
    const val ST_GRID = "GRID"
    const val ST_DIALOG = "DIALOG"
    const val ST_BATTLE = "BATTLE"
    const val ST_RESULT = "RESULT"
    const val ST_TOWN = "TOWN"
    const val ST_GUILD_HOME = "GUILD_HOME"
    const val ST_BACKABLE = "BACKABLE"
    const val ST_UNKNOWN = "UNKNOWN"

    const val MATCH_THRESHOLD = 0.75
    const val MATCH_MARGIN = 90
    const val BAR_FULL_RATIO = 0.98
    const val CELL_DARK_RATIO = 0.35
    const val CELL_LIGHT_RATIO = 0.30
    const val DECK_LIGHT_VALUE = 150.0
    const val DECK_CHECK_MIN = 60
    const val CHANGE_THRESHOLD = 25.0
    const val SETTLE_MS = 120L
    const val IDLE_WAIT_MS = 3000L
    const val UNKNOWN_LIMIT = 60

    /** Hai nút "Tấn công" giống hệt nhau nhưng lệch ~70px nên vùng tìm phải hẹp. */
    val MARGIN_OVERRIDE: Map<String, Int> = mapOf(
        "main_attack" to 25, "dialog_attack" to 25, "grid_defeated" to 40, "back_arrow" to 20,
        "town_lapnhom" to 40, "town_banghoi" to 40, "guild_play" to 40
    )

    /** Vị trí gốc của ảnh mẫu trong ảnh 2400x1080: x0, y0, x1, y1. */
    val TEMPLATES: Map<String, IntArray> = linkedMapOf(
        "game_chon" to intArrayOf(237, 31, 579, 95),
        "main_attack" to intArrayOf(1347, 967, 1668, 1028),
        "grid_clock" to intArrayOf(1892, 40, 2120, 86),
        "grid_label" to intArrayOf(992, 135, 1133, 171),
        "dialog_close" to intArrayOf(834, 899, 1209, 973),
        "dialog_attack" to intArrayOf(1316, 895, 1668, 963),
        "grid_defeated" to intArrayOf(895, 566, 1612, 610),
        "battle_pause" to intArrayOf(2281, 31, 2370, 104),
        "result_mainmenu" to intArrayOf(1140, 964, 1370, 1028),
        "back_arrow" to intArrayOf(138, 24, 227, 99),
        "town_lapnhom" to intArrayOf(172, 1005, 342, 1052),
        "town_banghoi" to intArrayOf(1165, 1005, 1320, 1052),
        "guild_play" to intArrayOf(1225, 950, 1445, 1012)
    )

    val TAP_BANG_CHIEN_CARD = Pt(888, 581)
    val TAP_BACK = Pt(181, 61)
    val TAP_MAIN_ATTACK = Pt(1508, 994)
    val TAP_MAIN_NEWGUILD = Pt(1967, 994)
    val TAP_DIALOG_ATTACK = Pt(1492, 933)
    val TAP_DIALOG_CLOSE = Pt(1018, 933)
    val TAP_RESULT_LOBBY = Pt(946, 994)
    val TAP_RESULT_CONTINUE = Pt(1558, 994)
    /** 5 ô nhân vật khi đánh. */
    val BATTLE_CARDS = arrayOf(Pt(1022, 978), Pt(1302, 978), Pt(1582, 978), Pt(1862, 978), Pt(2142, 978))
    val TAP_TOWN_GUILD = Pt(1242, 940)
    val TAP_GUILD_PLAY = Pt(1305, 982)

    const val DECK_RADIO_X = 768
    val DECK_ROW_Y = intArrayOf(505, 633, 762)
    const val DECK_SAMPLE_X0 = 827
    const val DECK_SAMPLE_X1 = 1071
    const val DECK_CHECK_X0 = 735
    const val DECK_CHECK_X1 = 815

    /** x0, y0, x1, y1 của phần bên trong thanh tiến trình. */
    val MAIN_BAR = intArrayOf(1578, 864, 1899, 883)
    val GRID_BAR = intArrayOf(1146, 143, 1468, 162)

    /** Hình chữ nhật của ô nhà số i (1..30) trong lưới. */
    fun cellRect(i: Int): IntArray {
        val r = (i - 1) / 5
        val c = (i - 1) % 5
        val top = 210.0 + 130.4 * r
        val left = (if (r % 2 == 0) 471.0 else 298.0) + 349.8 * c
        return intArrayOf(left.toInt(), top.toInt(), (left + 338.0).toInt(), (top + 119.0).toInt())
    }

    fun cellCenter(i: Int): Pt {
        val q = cellRect(i)
        return Pt((q[0] + q[2]) / 2, (q[1] + q[3]) / 2)
    }
}
