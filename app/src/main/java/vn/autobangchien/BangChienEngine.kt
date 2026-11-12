package vn.autobangchien

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ máy chạy kịch bản Bang chiến. Port đầy đủ từ bản máy tính (auto_bang_chien.py):
 * nhận diện màn hình -> bấm -> chờ màn hình đổi, bấm 5 ô nhân vật khi đánh, kiểm tra dấu tích chọn deck,
 * xử lý banner "Đã bị đánh bại", tự quay lại khi bấm nhầm và tự vào lại Bang hội -> Trò chơi.
 */
class BangChienEngine(
    private val ctx: Context,
    private val vision: Vision,
    private val source: FrameSource
) {
    @Volatile private var stopFlag = false
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

    fun requestStop() {
        stopFlag = true
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

    // ------------------------------------------------------------------ bấm
    private fun tap(p: Pt, frame: Frame? = null, anchor: String? = null, template: String? = null) {
        val s = BotAccessibilityService.instance
        if (s == null) {
            BotLog.e("Dịch vụ Trợ năng chưa kết nối nên không bấm được. Hãy bật lại Trợ năng.")
            sleep(1000)
            return
        }
        // Nếu có ảnh mẫu, ưu tiên bấm đúng tâm nút vừa tìm thấy trên ảnh hiện tại.
        // Nếu không, hiệu chỉnh điểm theo anchor của màn hình để bù dịch chuyển/co giãn UI.
        val logical = if (frame != null && template != null) {
            val found = vision.locate(frame, template)?.takeIf { it.score >= Cfg.MATCH_THRESHOLD }
            if (found != null) {
                BotLog.i("Tìm thấy nút $template: (${found.center.x},${found.center.y}), độ khớp=${String.format(Locale.US, "%.2f", found.score)}, tỷ lệ=${found.scaleX}x${found.scaleY}")
            } else {
                BotLog.i("Không tìm thấy ảnh mẫu $template đủ tin cậy; dùng tọa độ dự phòng.")
                saveDebug(frame, "tapmiss_" + template)
            }
            found?.center ?: if (anchor != null) vision.adjustPoint(frame, anchor, p) else p
        } else if (frame != null && anchor != null) {
            vision.adjustPoint(frame, anchor, p)
        } else p
        // Dùng chính Frame đã nhận diện nút để tránh lệch nếu kích thước capture vừa thay đổi.
        val screen = frame?.toScreen(logical) ?: source.toScreen(logical)
        s.enqueueTap(screen.x, screen.y)
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
    private fun tapWait(
        p: Pt,
        frame: Frame,
        timeoutMs: Long,
        anchor: String? = null,
        template: String? = null
    ) {
        tap(p, frame, anchor, template)
        val t0 = now()
        var changed = false
        while (!stopFlag && now() - t0 < timeoutMs) {
            sleep(40)
            val cur = source.grab()
            if (cur == null) continue
            if (!changed && frame.diff(cur) >= Cfg.CHANGE_THRESHOLD) changed = true
            if (changed) {
                if (vision.identify(cur) != Cfg.ST_UNKNOWN) {
                    cache = cur
                    return
                }
                sleep(Cfg.SETTLE_MS)
            }
        }
    }

    private fun saveDebug(frame: Frame, tag: String) {
        if (savedDebug >= 12) return
        try {
            val dir = File(ctx.getExternalFilesDir(null), "debug")
            dir.mkdirs()
            val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val f = File(dir, tag + "_" + stamp + ".jpg")
            FileOutputStream(f).use { out ->
                frame.toBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
            File(dir, tag + "_" + stamp + ".txt").writeText(
                "capture=${frame.w}x${frame.h}\n" +
                    "content=${frame.contentLeft},${frame.contentTop},${frame.contentRight},${frame.contentBottom}\n" +
                    "reference=${Cfg.REF_W}x${Cfg.REF_H}\n" +
                    "hint=Ảnh gốc; vùng content là vùng sau khi bỏ viền đen tự phát hiện.\n"
            )
            savedDebug++
            BotLog.i("Đã lưu ảnh và thông tin tọa độ để kiểm tra: " + f.absolutePath)
        } catch (e: Exception) {
            // không lưu được thì thôi
        }
    }

    // ------------------------------------------------------------------ vòng lặp chính
    fun run() {
        BotLog.i("Bot bắt đầu.")
        var waited = 0
        var firstFrame: Frame? = null
        while (!stopFlag && firstFrame == null) {
            firstFrame = source.grab()
            if (firstFrame == null) {
                sleep(100)
                waited++
                if (waited > 80) {
                    BotLog.e("Không nhận được hình ảnh màn hình. Hãy chọn 'Toàn bộ màn hình' khi chia sẻ.")
                    return
                }
            }
        }
        if (firstFrame != null) {
            BotLog.i("Màn hình chụp: ${firstFrame.w}x${firstFrame.h}; vùng game: " +
                "${firstFrame.contentLeft},${firstFrame.contentTop} - ${firstFrame.contentRight},${firstFrame.contentBottom}")
            cache = firstFrame
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
            var state = vision.identify(cur)

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
                Cfg.ST_LOBBY -> tapWait(Cfg.TAP_BANG_CHIEN_CARD, cur, 3000)
                Cfg.ST_MAIN -> onMain(cur)
                Cfg.ST_GRID -> onGrid(cur)
                Cfg.ST_DIALOG -> onDialog(cur)
                Cfg.ST_BATTLE -> onBattle()
                Cfg.ST_RESULT -> onResult(cur)
                Cfg.ST_TOWN -> {
                    BotLog.i("Đang ở màn hình chính -> bấm Bang hội")
                    tapWait(Cfg.TAP_TOWN_GUILD, cur, 4000, anchor = "town_banghoi")
                }
                Cfg.ST_GUILD_HOME -> {
                    BotLog.i("Đang ở màn Bang hội -> bấm Trò chơi")
                    tapWait(Cfg.TAP_GUILD_PLAY, cur, 4000, anchor = "guild_play")
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
        val ratio = vision.barRatio(frame, Cfg.MAIN_BAR, "main_attack")
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
            tap(Cfg.TAP_MAIN_NEWGUILD, frame, anchor = "main_attack")
            val t0 = now()
            while (!stopFlag && now() - t0 < 4000) {
                sleep(150)
                val g = source.grab()
                if (g != null && vision.barRatio(g, Cfg.MAIN_BAR, "main_attack") < Cfg.BAR_FULL_RATIO) break
            }
            sleep(200)
            lastNewGuild = now()
            guilds++
            BotLog.guilds = guilds
            blocked.clear()
            totalHouses = 0
        } else {
            // Chưa đầy -> bấm "Tấn công" vào lưới 30 ô
            tapWait(Cfg.TAP_MAIN_ATTACK, frame, 3000, template = "main_attack")
        }
    }

    private fun gridDone(f: Frame): Boolean {
        return vision.barRatio(f, Cfg.GRID_BAR, "grid_label") >= Cfg.BAR_FULL_RATIO ||
            vision.score(f, "grid_defeated") >= Cfg.MATCH_THRESHOLD
    }

    private fun targetsOf(cells: IntArray): List<Int> {
        val out = ArrayList<Int>()
        for (i in 1..30) {
            if (cells[i] == Vision.LIGHT && !blocked.contains(i)) out.add(i)
        }
        return out
    }

    private fun onGrid(frame: Frame) {
        // Phải kiểm tra TRƯỚC khi quét ô: banner trắng đè lên lưới làm ô trống trông như ô đánh được.
        if (gridDone(frame)) {
            BotLog.i("Bang đối thủ đã bị đánh bại -> quay lại để ghép bang hội mới")
            tapWait(Cfg.TAP_BACK, frame, 3000, template = "back_arrow")
            return
        }
        var f = frame
        var cells = vision.scanCells(f)
        var targets = targetsOf(cells)
        // Ảnh có thể chụp lúc màn hình đang hiện dần -> quét lại một lần cho chắc
        if (targets.isEmpty()) {
            sleep(400)
            val f2 = source.grab()
            if (f2 == null) return
            if (vision.identify(f2) != Cfg.ST_GRID || gridDone(f2)) {
                cache = f2
                return
            }
            f = f2
            cells = vision.scanCells(f)
            targets = targetsOf(cells)
        }
        val total = cells.count { it != Vision.EMPTY }
        if (total > 0) totalHouses = total
        if (targets.isNotEmpty()) {
            val t = targets[0]
            BotLog.i("Bang có " + total + " nhà, chọn ô " + t)
            dialogTries = 0
            pending = t
            tapWait(vision.cellCenter(f, t), f, 3000)
        } else {
            BotLog.i("Không còn ô nào đánh được (" + total + " nhà) -> quay lại kiểm tra tiến trình")
            tapWait(Cfg.TAP_BACK, f, 3000, template = "back_arrow")
            sleep(Cfg.IDLE_WAIT_MS)
        }
    }

    private fun onDialog(frame: Frame) {
        val avail = vision.deckAvailable(frame)
        dialogTries++
        if (dialogTries == 4) saveDebug(frame, "dialog")
        var any = false
        for (a in avail) {
            if (a) any = true
        }
        if (!any || dialogTries > 10) {
            BotLog.i("Hết deck hoặc không bấm được -> đóng bảng, bỏ qua ô " + pending)
            if (pending > 0) blocked.add(pending)
            tapWait(Cfg.TAP_DIALOG_CLOSE, frame, 2000, template = "dialog_close")
            return
        }
        var want = 0
        for (i in 0 until 3) {
            if (avail[i]) {
                want = i
                break
            }
        }
        val sel = vision.selectedDeck(frame, avail)
        var f = frame
        // Chưa chọn đúng deck -> bấm chọn, rồi CHỜ thấy dấu tích mới bấm Tấn công.
        // Nếu vài lần liên tiếp không thấy dấu tích thì vẫn bấm Tấn công (phòng nhận diện dấu tích sai).
        if (sel != want && dialogTries < 3) {
            BotLog.i("Chọn Deck " + (want + 1))
            tap(Pt(Cfg.DECK_RADIO_X, Cfg.DECK_ROW_Y[want]), frame, anchor = "dialog_close")
            val t0 = now()
            var ok = false
            while (!stopFlag && now() - t0 < 1500) {
                sleep(100)
                val g = source.grab()
                if (g != null) {
                    f = g
                    if (vision.selectedDeck(g, avail) == want) {
                        ok = true
                        break
                    }
                }
            }
            if (!ok) return
        }
        BotLog.i("Deck " + (want + 1) + " đã chọn -> bấm Tấn công")
        tapWait(Cfg.TAP_DIALOG_ATTACK, f, 5000, template = "dialog_attack")
    }

    private fun onBattle() {
        if (last != Cfg.ST_BATTLE) {
            battles++
            BotLog.battles = battles
            BotLog.i("Vào trận #" + battles)
        }
        // Giữ hàng đợi bấm luôn có việc; vòng sau chụp màn hình chạy song song với việc bấm
        if (pendingTaps() <= 4) {
            val battleFrame = source.grab()
            for (c in Cfg.BATTLE_CARDS) tap(c, battleFrame, anchor = "battle_pause")
        }
    }

    private fun onResult(frame: Frame) {
        // Bang chỉ có 1 nhà (0/1) -> ưu tiên Lobby, còn lại bấm Tiếp tục
        if (totalHouses == 1) {
            BotLog.i("Bang 0/1 nhà -> bấm Lobby")
            tapWait(Cfg.TAP_RESULT_LOBBY, frame, 4000, anchor = "result_mainmenu")
        } else {
            BotLog.i("Bấm Tiếp tục")
            tapWait(Cfg.TAP_RESULT_CONTINUE, frame, 4000, anchor = "result_mainmenu")
        }
    }

    private fun onBackable(frame: Frame) {
        // Màn hình không có trong kịch bản nhưng có mũi tên quay lại (do bấm nhầm) -> bấm quay lại
        backCount++
        if (backCount > 25) {
            BotLog.e("Bấm quay lại 25 lần vẫn kẹt ở một màn hình lạ. Đã dừng.")
            saveDebug(frame, "stuck")
            stopFlag = true
            return
        }
        if (same < 1) {
            sleep(300)
            return
        }
        if (backCount == 2) saveDebug(frame, "backable")
        BotLog.i("Màn hình lạ có nút quay lại -> bấm quay lại")
        tapWait(Cfg.TAP_BACK, frame, 3000, template = "back_arrow")
    }

    private fun onUnknown(frame: Frame) {
        if (unknownCount == 1 || unknownCount % 10 == 0) saveDebug(frame, "unknown")
        if (unknownCount >= Cfg.UNKNOWN_LIMIT) {
            BotLog.e("Gặp màn hình lạ " + Cfg.UNKNOWN_LIMIT + " lần liên tiếp (popup, mất mạng, văng game...). Đã dừng để tránh bấm bậy.")
            stopFlag = true
            return
        }
        sleep(400)
    }
}
