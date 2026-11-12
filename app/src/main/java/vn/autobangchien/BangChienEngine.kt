package vn.autobangchien

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ máy chạy kịch bản Bang chiến (port từ bản máy tính). Điểm khác biệt quan trọng để chạy được
 * trên mọi màn hình: không dùng toạ độ cố định mà dùng VỊ TRÍ THẬT của từng nút do Vision dò được
 * (độ dịch so với máy chuẩn), rồi mới quy ra pixel của màn hình hiện tại.
 */
class BangChienEngine(
    private val ctx: Context,
    private val vision: Vision,
    private val source: FrameSource
) {
    @Volatile private var stopFlag = false
    @Volatile private var snapAt = 0L
    private val maxBattles: Int = ctx.getSharedPreferences("bot", Context.MODE_PRIVATE).getInt("max", 0)

    private var totalHouses = 0
    private val blocked = HashSet<Int>()
    private var lastNewGuild = 0L
    private var last = ""
    private var same = 0
    private var unknownCount = 0
    private var battleUnknown = 0
    private var backCount = 0
    private var battles = 0
    private var guilds = 0
    private var dialogTries = 0
    private var pending = 0
    private var cache: Frame? = null
    private var savedDebug = 0
    private var lastBand = 0L
    private var lastProbe = 0L
    private var announced = false
    private var battleCards: List<Pt> = ArrayList<Pt>()
    private var cardsAt = 0L

    fun requestStop() {
        stopFlag = true
    }

    /** Chụp một ảnh game sau `delayMs` mili giây (để bạn kịp chuyển sang game) và lưu vào Thư viện. */
    fun requestSnapshot(delayMs: Long) {
        snapAt = SystemClock.elapsedRealtime() + delayMs
        BotLog.i("Sẽ chụp ảnh sau " + (delayMs / 1000) + " giây. Hãy chuyển sang game ngay.")
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && !stopFlag) {
            val s = if (left > 40L) 40L else left
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                stopFlag = true
            }
            left -= s
        }
    }

    // ------------------------------------------------------------------ toạ độ và bấm
    private fun at(p: Pt, s: IntArray): Pt = Pt(p.x + s[0], p.y + s[1])

    /** Độ dịch dự phòng cho nhóm giao diện neo giữa màn hình. */
    private fun centerShift(f: Frame): IntArray =
        intArrayOf(Math.round(f.refW / 2.0 - Cfg.REF_CENTER_X).toInt(), 0)

    /** Bấm tại điểm (toạ độ chuẩn đã dịch), quy ra pixel thật theo tỉ lệ của khung hình. */
    private fun tapAt(p: Pt, f: Frame) {
        val s = BotAccessibilityService.instance
        if (s == null) {
            BotLog.e("Dịch vụ Trợ năng chưa kết nối nên không bấm được. Hãy bật lại Trợ năng.")
            sleep(1000)
            return
        }
        s.enqueueTap((p.x * f.scale).toInt(), (p.y * f.scale).toInt())
    }

    private fun pendingTaps(): Int {
        val s = BotAccessibilityService.instance
        return if (s == null) 0 else s.pendingTaps()
    }

    private fun abortTaps() {
        val s = BotAccessibilityService.instance
        if (s != null) s.abortQueued()
    }

    /** Bấm rồi chờ đến khi màn hình mới hiện ra; khung hình nhận ra được thì dùng luôn cho vòng sau. */
    private fun tapWait(p: Pt, frame: Frame, timeoutMs: Long) {
        tapAt(p, frame)
        val t0 = now()
        var changed = false
        while (!stopFlag && now() - t0 < timeoutMs) {
            sleep(40)
            val cur = source.grab()
            if (cur == null) continue
            if (!changed && frame.diff(cur) >= Cfg.CHANGE_THRESHOLD) changed = true
            if (changed) {
                if (vision.identify(cur, false) != Cfg.ST_UNKNOWN) {
                    cache = cur
                    return
                }
                sleep(Cfg.SETTLE_MS)
            }
        }
    }

    // ------------------------------------------------------------------ lưu ảnh
    /** Lưu ảnh vào Thư viện (thư mục Pictures/AutoBangChien) để gửi đi; không được thì lưu vào bộ nhớ ứng dụng. */
    private fun saveShot(frame: Frame, tag: String) {
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val name = tag + "_" + stamp + ".jpg"
        try {
            val cv = ContentValues()
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoBangChien")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) {
                saveDebug(frame, tag)
                return
            }
            val os = ctx.contentResolver.openOutputStream(uri)
            if (os != null) {
                os.use { out -> frame.toBitmap().compress(Bitmap.CompressFormat.JPEG, 90, out) }
            }
            BotLog.i("Đã lưu ảnh vào Thư viện > AutoBangChien: " + name)
        } catch (e: Exception) {
            saveDebug(frame, tag)
        }
    }

    private fun saveDebug(frame: Frame, tag: String) {
        try {
            val dir = File(ctx.getExternalFilesDir(null), "debug")
            dir.mkdirs()
            val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val f = File(dir, tag + "_" + stamp + ".jpg")
            FileOutputStream(f).use { out ->
                frame.toBitmap().compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            BotLog.i("Đã lưu ảnh để kiểm tra: " + f.absolutePath)
        } catch (e: Exception) {
            // không lưu được thì thôi
        }
    }

    private fun debugShot(frame: Frame, tag: String) {
        if (savedDebug >= 12) return
        savedDebug++
        saveShot(frame, tag)
    }

    // ------------------------------------------------------------------ nhận diện thích nghi
    /**
     * Nhận diện màn hình: trước hết tìm quanh vị trí đã nhớ (nhanh). Nếu chưa nhận ra (hoặc chỉ thấy
     * nút quay lại) thì dò theo dải ngang trên toàn bề ngang để học bố cục của thiết bị này.
     */
    private fun identify(f: Frame): String {
        var st = vision.identify(f, false)
        if ((st == Cfg.ST_UNKNOWN || st == Cfg.ST_BACKABLE) && now() - lastBand >= 1200) {
            lastBand = now()
            st = vision.identify(f, true)
        }
        if (st != Cfg.ST_UNKNOWN && st != Cfg.ST_BACKABLE) vision.layout.validated = true
        return st
    }

    /** Thiết bị mới mà chưa nhận ra màn hình nào: thử các mức tỉ lệ giao diện khác. */
    private fun probeScales(frame: Frame) {
        lastProbe = now()
        BotLog.i("Chưa nhận ra giao diện, đang thử các tỉ lệ khác...")
        val old = vision.layout
        for (m in Cfg.SCALE_MULS) {
            if (m == old.mul) continue
            val lay = Layout()
            lay.mul = m
            vision.layout = lay
            val f2 = frame.rescaled(frame.h / 1080.0 * m)
            val st = vision.identify(f2, true)
            if (st != Cfg.ST_UNKNOWN && st != Cfg.ST_BACKABLE) {
                lay.validated = true
                source.mul = m
                BotLog.i("Đã tìm ra tỉ lệ giao diện phù hợp: x" + m)
                return
            }
        }
        vision.layout = old
    }

    // ------------------------------------------------------------------ vòng lặp chính
    fun run() {
        BotLog.i("Bot bắt đầu.")
        var waited = 0
        while (!stopFlag && source.grab() == null) {
            sleep(100)
            waited++
            if (waited > 80) {
                BotLog.e("Không nhận được hình ảnh màn hình. Hãy chọn 'Toàn bộ màn hình' khi chia sẻ.")
                return
            }
        }
        while (!stopFlag) {
            if (maxBattles > 0 && battles >= maxBattles) {
                BotLog.i("Đã đủ " + maxBattles + " trận, dừng.")
                break
            }
            var frame: Frame? = cache
            cache = null
            if (frame == null) frame = source.grab()
            if (frame == null) {
                sleep(50)
                continue
            }
            val cur: Frame = frame
            if (!announced) {
                announced = true
                BotLog.i("Màn hình " + cur.w + "x" + cur.h + ", tỉ lệ giao diện x" + String.format(Locale.US, "%.2f", cur.scale))
            }
            if (snapAt > 0L && now() >= snapAt) {
                snapAt = 0L
                saveShot(cur, "snap")
            }
            var state = identify(cur)

            // Màn hình lạ ngay sau BATTLE (hoạt ảnh thắng/thua) thì vẫn coi là đang đánh
            if (state == Cfg.ST_UNKNOWN && last == Cfg.ST_BATTLE && battleUnknown < 40) {
                battleUnknown++
                state = Cfg.ST_BATTLE
            } else if (state != Cfg.ST_UNKNOWN) {
                battleUnknown = 0
            }

            // Hết trận: huỷ ngay các lần bấm còn xếp hàng để không bấm lạc vào nút ở màn kết quả
            if (last == Cfg.ST_BATTLE && state != Cfg.ST_BATTLE) abortTaps()

            if (state == last) {
                same++
            } else {
                same = 0
                BotLog.i("Màn hình: " + state)
            }
            if (state == Cfg.ST_UNKNOWN) {
                unknownCount++
            } else if (state != Cfg.ST_BATTLE) {
                unknownCount = 0
            }
            if (state != Cfg.ST_BACKABLE) backCount = 0

            when (state) {
                Cfg.ST_LOBBY -> {
                    val s = vision.shiftFor(cur, "lobby_card", centerShift(cur))
                    tapWait(at(Cfg.TAP_BANG_CHIEN_CARD, s), cur, 3000)
                }
                Cfg.ST_MAIN -> onMain(cur)
                Cfg.ST_GRID -> onGrid(cur)
                Cfg.ST_DIALOG -> onDialog(cur)
                Cfg.ST_BATTLE -> onBattle(cur)
                Cfg.ST_RESULT -> onResult(cur)
                Cfg.ST_TOWN -> {
                    BotLog.i("Đang ở màn hình chính -> bấm Bang hội")
                    val s = vision.shiftFor(cur, "town_banghoi", vision.anchor("town_lapnhom"))
                    tapWait(at(Cfg.TAP_TOWN_GUILD, s), cur, 4000)
                }
                Cfg.ST_GUILD_HOME -> {
                    BotLog.i("Đang ở màn Bang hội -> bấm Trò chơi")
                    val s = vision.shiftFor(cur, "guild_play", null)
                    tapWait(at(Cfg.TAP_GUILD_PLAY, s), cur, 4000)
                }
                Cfg.ST_BACKABLE -> onBackable(cur)
                else -> onUnknown(cur)
            }
            last = state
            sleep(15)
        }
        abortTaps()
        BotLog.i("Bot đã dừng.")
    }

    // ------------------------------------------------------------------ xử lý từng màn hình
    private fun onMain(frame: Frame) {
        val s = vision.anchor("main_attack")
        val ratio = vision.barRatio(frame, Cfg.MAIN_BAR, s)
        val full = ratio >= Cfg.BAR_FULL_RATIO
        if (same == 0) {
            BotLog.i("Tiến trình bang đối thủ: " + (ratio * 100).toInt() + "%" + (if (full) " (ĐẦY)" else ""))
        }
        if (full) {
            // Đầy -> bấm "Bang hội mới". Chờ sau mỗi lần bấm để tránh bấm đúp.
            if (now() - lastNewGuild < 3000) {
                sleep(200)
                return
            }
            BotLog.i("Bang đã clear hết -> tìm Bang hội mới")
            tapAt(at(Cfg.TAP_MAIN_NEWGUILD, s), frame)
            val t0 = now()
            while (!stopFlag && now() - t0 < 4000) {
                sleep(150)
                val g = source.grab()
                if (g != null && vision.barRatio(g, Cfg.MAIN_BAR, s) < Cfg.BAR_FULL_RATIO) break
            }
            sleep(200)
            lastNewGuild = now()
            guilds++
            BotLog.guilds = guilds
            blocked.clear()
            totalHouses = 0
        } else {
            // Chưa đầy -> bấm "Tấn công" vào lưới 30 ô
            tapWait(at(Cfg.TAP_MAIN_ATTACK, s), frame, 3000)
        }
    }

    private fun gridDone(f: Frame, s: IntArray): Boolean {
        return vision.barRatio(f, Cfg.GRID_BAR, s) >= Cfg.BAR_FULL_RATIO ||
            vision.scoreAt(f, "grid_defeated", s) >= Cfg.MATCH_THRESHOLD
    }

    private fun targetsOf(cells: IntArray): List<Int> {
        val out = ArrayList<Int>()
        for (i in 1..30) {
            if (cells[i] == Vision.LIGHT && !blocked.contains(i)) out.add(i)
        }
        return out
    }

    private fun onGrid(frame: Frame) {
        // Toàn bộ lưới và thanh tiến trình neo cùng nhóm với chữ "Tiến trình"
        val s = vision.shiftFor(frame, "grid_label", centerShift(frame))
        val b = vision.shiftFor(frame, "back_arrow", null)
        // Phải kiểm tra TRƯỚC khi quét ô: banner trắng đè lên lưới làm ô trống trông như ô đánh được.
        if (gridDone(frame, s)) {
            BotLog.i("Bang đối thủ đã bị đánh bại -> quay lại để ghép bang hội mới")
            tapWait(at(Cfg.TAP_BACK, b), frame, 3000)
            return
        }
        var f = frame
        var cells = vision.scanCells(f, s)
        var targets = targetsOf(cells)
        // Ảnh có thể chụp lúc màn hình đang hiện dần -> quét lại một lần cho chắc
        if (targets.isEmpty()) {
            sleep(400)
            val f2 = source.grab()
            if (f2 == null) return
            if (vision.identify(f2, false) != Cfg.ST_GRID || gridDone(f2, s)) {
                cache = f2
                return
            }
            f = f2
            cells = vision.scanCells(f, s)
            targets = targetsOf(cells)
        }
        val total = cells.count { it != Vision.EMPTY }
        if (total > 0) totalHouses = total
        if (targets.isNotEmpty()) {
            val t = targets[0]
            BotLog.i("Bang có " + total + " nhà, chọn ô " + t)
            dialogTries = 0
            pending = t
            tapWait(at(Cfg.cellCenter(t), s), f, 3000)
        } else {
            BotLog.i("Không còn ô nào đánh được (" + total + " nhà) -> quay lại kiểm tra tiến trình")
            tapWait(at(Cfg.TAP_BACK, b), f, 3000)
            sleep(Cfg.IDLE_WAIT_MS)
        }
    }

    private fun onDialog(frame: Frame) {
        // Cả bảng chọn deck là một khối: neo theo nút "Đóng" (không thấy thì theo nút "Tấn công")
        val s = vision.shiftFor(frame, "dialog_close", vision.anchor("dialog_attack"))
        val sa = vision.shiftFor(frame, "dialog_attack", s)
        val avail = vision.deckAvailable(frame, s)
        dialogTries++
        if (dialogTries == 4) debugShot(frame, "dialog")
        var any = false
        for (a in avail) {
            if (a) any = true
        }
        if (!any || dialogTries > 10) {
            BotLog.i("Hết deck hoặc không bấm được -> đóng bảng, bỏ qua ô " + pending)
            if (pending > 0) blocked.add(pending)
            tapWait(at(Cfg.TAP_DIALOG_CLOSE, s), frame, 2000)
            return
        }
        var want = 0
        for (i in 0 until 3) {
            if (avail[i]) {
                want = i
                break
            }
        }
        val sel = vision.selectedDeck(frame, avail, s)
        var f = frame
        // Chưa chọn đúng deck -> bấm chọn, rồi CHỜ thấy dấu tích mới bấm Tấn công.
        // Nếu vài lần liên tiếp không thấy dấu tích thì vẫn bấm Tấn công (phòng nhận diện dấu tích sai).
        if (sel != want && dialogTries < 3) {
            BotLog.i("Chọn Deck " + (want + 1))
            tapAt(at(Pt(Cfg.DECK_RADIO_X, Cfg.DECK_ROW_Y[want]), s), frame)
            val t0 = now()
            var ok = false
            while (!stopFlag && now() - t0 < 1500) {
                sleep(100)
                val g = source.grab()
                if (g != null) {
                    f = g
                    if (vision.selectedDeck(g, avail, s) == want) {
                        ok = true
                        break
                    }
                }
            }
            if (!ok) return
        }
        BotLog.i("Deck " + (want + 1) + " đã chọn -> bấm Tấn công")
        tapWait(at(Cfg.TAP_DIALOG_ATTACK, sa), f, 5000)
    }

    private fun onBattle(frame: Frame) {
        if (last != Cfg.ST_BATTLE) {
            battles++
            BotLog.battles = battles
            BotLog.i("Vào trận #" + battles)
            cardsAt = 0L
        }
        // Dò vị trí các ô nhân vật theo viên ngọc dưới mỗi ô (đầu trận và định kỳ 3 giây)
        if (battleCards.isEmpty() || now() - cardsAt > 3000) {
            val found = vision.findCards(frame)
            cardsAt = now()
            if (found.isNotEmpty()) {
                if (found.size != battleCards.size) BotLog.i("Tìm thấy " + found.size + " ô nhân vật")
                battleCards = found
            } else if (battleCards.isEmpty()) {
                // Dự phòng: coi cụm ô neo sát cạnh phải màn hình
                val rs = frame.refW - Cfg.REF_W
                val fb = ArrayList<Pt>()
                for (c in Cfg.BATTLE_CARDS) fb.add(Pt(c.x + rs, c.y))
                battleCards = fb
                BotLog.i("Không dò được ô nhân vật, dùng vị trí dự phòng.")
            }
        }
        // Giữ hàng đợi bấm luôn có việc; vòng sau chụp màn hình chạy song song với việc bấm
        if (pendingTaps() <= 4) {
            for (c in battleCards) tapAt(c, frame)
        }
    }

    private fun onResult(frame: Frame) {
        val s = vision.anchor("result_mainmenu")
        // Bang chỉ có 1 nhà (0/1) -> ưu tiên Lobby, còn lại bấm Tiếp tục
        if (totalHouses == 1) {
            BotLog.i("Bang 0/1 nhà -> bấm Lobby")
            tapWait(at(Cfg.TAP_RESULT_LOBBY, s), frame, 4000)
        } else {
            BotLog.i("Bấm Tiếp tục")
            tapWait(at(Cfg.TAP_RESULT_CONTINUE, s), frame, 4000)
        }
    }

    private fun onBackable(frame: Frame) {
        // Màn hình không có trong kịch bản nhưng có mũi tên quay lại (do bấm nhầm) -> bấm quay lại
        backCount++
        if (backCount > 25) {
            BotLog.e("Bấm quay lại 25 lần vẫn kẹt ở một màn hình lạ. Đã dừng.")
            saveShot(frame, "stuck")
            stopFlag = true
            return
        }
        if (same < 1) {
            sleep(300)
            return
        }
        if (backCount == 2) debugShot(frame, "backable")
        BotLog.i("Màn hình lạ có nút quay lại -> bấm quay lại")
        val s = vision.shiftFor(frame, "back_arrow", null)
        tapWait(at(Cfg.TAP_BACK, s), frame, 3000)
    }

    private fun onUnknown(frame: Frame) {
        if (unknownCount == 1 || unknownCount % 10 == 0) debugShot(frame, "unknown")
        // Thiết bị mới chưa từng nhận ra màn hình nào: thử các tỉ lệ giao diện khác
        if (!vision.layout.validated && unknownCount >= 8 && now() - lastProbe >= 6000) {
            probeScales(frame)
        }
        if (unknownCount >= Cfg.UNKNOWN_LIMIT) {
            BotLog.e("Gặp màn hình lạ " + Cfg.UNKNOWN_LIMIT + " lần liên tiếp (popup, mất mạng, văng game...). Đã dừng để tránh bấm bậy.")
            saveShot(frame, "stopped")
            stopFlag = true
            return
        }
        sleep(400)
    }
}
