package vn.autobangchien

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Native-phone port of the latest Python bot state machine. Coordinates remain in 2400x1080 reference space. */
class BangChienEngine(private val context: Context) : AutoCloseable {
    companion object {
        private const val BAR_FULL_RATIO = 0.98
        private const val BATTLE_TAP_ROUNDS = 2
        private const val BATTLE_MAX_INFLIGHT_BATCHES = 1
        private const val IDLE_WAIT_MS = 3_000L
        private const val CHANGE_THRESHOLD = 25.0
        private const val SETTLE_MS = 120L
        private val MAIN_BAR = intArrayOf(1578, 864, 1899, 883)
        private val BATTLE_CARDS = listOf(1018 to 979, 1286 to 979, 1584 to 979)
    }

    private val vision = BangChienVision(context.assets)
    private val prefs = context.getSharedPreferences("bot", Context.MODE_PRIVATE)
    private val maxBattles = prefs.getInt("max", 0).coerceAtLeast(0)
    private val dryRun = prefs.getBoolean("dry_run", false)
    private val idleWaitMs = (prefs.getInt("idle_wait", 3).coerceIn(1, 30) * 1000L)

    private var lastState = BangChienVision.State.UNKNOWN
    private var sameCount = 0
    private var unknownCount = 0
    private var battleUnknown = 0
    private var battles = 0
    private var newGuilds = 0
    private var totalHouses: Int? = null
    private val blocked = mutableSetOf<Int>()
    private var pendingCell: Int? = null
    private var dialogTries = 0
    private var battleStartedAt = 0L
    private var lastNewGuildAt = 0L
    private var savedDebug = 0
    private var cachedFrame: BangChienVision.Frame? = null

    fun hasCachedFrame(): Boolean = cachedFrame != null

    /** Takes ownership of input. Pass null only when a frame was cached by tapWait(). */
    fun tick(input: Bitmap?, capture: () -> Bitmap?): Boolean {
        val frame = cachedFrame.also { cachedFrame = null } ?: run {
            if (input == null) return true
            vision.prepare(input)
        }
        try {
            if (!dryRun && !BotAccessibilityService.isReady) {
                BotEvents.setStatus(context, "● MẤT QUYỀN TRỢ NĂNG", "Dịch vụ Trợ năng không còn hoạt động; bot dừng an toàn")
                return false
            }
            if (maxBattles > 0 && battles >= maxBattles) {
                BotEvents.setStatus(context, "● ĐÃ ĐỦ TRẬN", "Đã đủ $maxBattles trận, dừng bot")
                return false
            }
            if (frame.displayWidth < frame.displayHeight) {
                BotEvents.setStatus(context, "● TẠM DỪNG", "Màn hình đang dọc. Hãy xoay game sang chế độ ngang.")
                return false
            }

            var (state, scores) = frame.identify()
            if (state == BangChienVision.State.UNKNOWN && lastState == BangChienVision.State.BATTLE && battleUnknown < 40) {
                battleUnknown++
                state = BangChienVision.State.BATTLE
            } else if (state != BangChienVision.State.UNKNOWN) {
                battleUnknown = 0
            }

            if (lastState == BangChienVision.State.BATTLE && state != BangChienVision.State.BATTLE && !dryRun) {
                BotAccessibilityService.abortPending()
                BotEvents.log(context, "Đã hủy các lượt chạm còn chờ sau khi trận kết thúc")
            }

            if (state == lastState) sameCount++ else {
                sameCount = 0
                val best = scores.maxByOrNull { it.value }
                BotEvents.log(context, "Màn hình: $state${best?.let { " | mẫu ${it.key}: ${"%.2f".format(it.value)}" } ?: ""}")
            }

            if (state == BangChienVision.State.UNKNOWN) {
                unknownCount++
                if (unknownCount == 1 || unknownCount % 10 == 0) saveUnknown(frame.bitmap)
                if (unknownCount >= 60) {
                    BotEvents.setStatus(context, "● DỪNG AN TOÀN", "Gặp màn hình lạ $unknownCount lần liên tiếp. Bot dừng để tránh chạm nhầm; xem thư mục debug.")
                    return false
                }
                Thread.sleep(400)
            } else if (state != BangChienVision.State.BATTLE) {
                unknownCount = 0
            }

            BotEvents.setStatus(context, "● ĐANG CHẠY", "Màn hình: $state | trận: $battles | bang mới: $newGuilds")
            when (state) {
                BangChienVision.State.LOBBY_SELECT -> onLobby(frame, capture)
                BangChienVision.State.MAIN -> onMain(frame, capture)
                BangChienVision.State.GRID -> onGrid(frame, capture)
                BangChienVision.State.DIALOG -> onDialog(frame, capture)
                BangChienVision.State.BATTLE -> onBattle(frame)
                BangChienVision.State.RESULT -> onResult(frame, capture)
                BangChienVision.State.UNKNOWN -> Unit
            }
            lastState = state
            return true
        } catch (e: Exception) {
            BotEvents.log(context, "Lỗi vòng nhận diện: ${e.javaClass.simpleName}: ${e.message}")
            Log.e("AutoBangChien", "Engine tick failed", e)
            return true
        } finally {
            frame.close()
        }
    }

    private fun onLobby(frame: BangChienVision.Frame, capture: () -> Bitmap?) {
        BotEvents.log(context, "Màn Game chọn -> mở Bang chiến")
        tapWait(888 to 581, frame, capture, 4_000)
    }

    private fun onMain(frame: BangChienVision.Frame, capture: () -> Bitmap?) {
        val full = frame.barRatio(MAIN_BAR) >= BAR_FULL_RATIO
        if (full) {
            if (System.currentTimeMillis() - lastNewGuildAt < 3_000) {
                Thread.sleep(200)
                return
            }
            BotEvents.log(context, "Thanh tiến trình đầy -> ghép Bang hội mới")
            if (!dryRun) {
                tap(1967 to 994, frame)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 4_000) {
                    val bitmap = capture()
                    if (bitmap == null) { Thread.sleep(100); continue }
                    val current = vision.prepare(bitmap)
                    val noLongerFull = current.barRatio(MAIN_BAR) < BAR_FULL_RATIO
                    if (noLongerFull) {
                        cachedFrame = current
                        break
                    }
                    current.close()
                    Thread.sleep(100)
                }
                Thread.sleep(200)
            } else Thread.sleep(500)
            lastNewGuildAt = System.currentTimeMillis()
            newGuilds++
            prefs.edit().putInt("guilds", newGuilds).apply()
            blocked.clear()
            totalHouses = null
        } else {
            BotEvents.log(context, "Thanh chưa đầy -> vào danh sách nhà để tấn công")
            tapWait(1508 to 994, frame, capture, 4_000)
        }
    }

    private fun onGrid(frame: BangChienVision.Frame, capture: () -> Bitmap?) {
        if (frame.gridDone()) {
            BotEvents.log(context, "Bang đối thủ đã bị đánh bại -> quay lại")
            tapWait(181 to 61, frame, capture, 3_000)
            return
        }
        var cells = frame.scanCells()
        var targets = cells.filter { it.value == "light" && it.key !in blocked }.keys
        var latestGridFrame: BangChienVision.Frame? = null
        if (targets.isEmpty()) {
            Thread.sleep(400)
            val bitmap = capture()
            if (bitmap != null) {
                val current = vision.prepare(bitmap)
                val (state, _) = current.identify()
                if (state != BangChienVision.State.GRID || current.gridDone()) {
                    cachedFrame = current
                    return
                }
                cells = current.scanCells()
                targets = cells.filter { it.value == "light" && it.key !in blocked }.keys
                if (cells.isNotEmpty()) totalHouses = cells.size
                if (targets.isNotEmpty()) {
                    val target = targets.minOrNull()!!
                    BotEvents.log(context, "Quét lại lưới: ${cells.size} nhà, chọn ô $target")
                    pendingCell = target; dialogTries = 0
                    tapWait(cellCenter(target), current, capture, 3_000)
                    current.close()
                    return
                }
                latestGridFrame = current
            }
        }
        if (cells.isNotEmpty()) totalHouses = cells.size
        if (targets.isNotEmpty()) {
            val target = targets.minOrNull()!!
            BotEvents.log(context, "Bang có ${cells.size} nhà, đánh được ${targets.sorted()} -> chọn ô $target")
            pendingCell = target
            dialogTries = 0
            tapWait(cellCenter(target), frame, capture, 3_000)
        } else {
            BotEvents.log(context, "Không còn ô đánh được (${cells.size} nhà) -> quay lại kiểm tra tiến trình")
            tapWait(181 to 61, latestGridFrame ?: frame, capture, 3_000)
            latestGridFrame?.close()
            Thread.sleep(idleWaitMs)
        }
    }

    private fun onDialog(frame: BangChienVision.Frame, capture: () -> Bitmap?) {
        val available = frame.deckAvailable()
        dialogTries++
        if (dialogTries == 4) saveUnknown(frame.bitmap)
        if (available.none { it } || dialogTries > 10) {
            BotEvents.log(context, "Hết deck hoặc không chọn được sau $dialogTries lần -> đóng bảng, bỏ qua ô $pendingCell")
            pendingCell?.let { blocked.add(it) }
            tapWait(1018 to 933, frame, capture, 2_000)
            return
        }
        val wanted = available.indexOfFirst { it }
        val selected = frame.selectedDeck(available)
        if (selected != wanted && dialogTries < 3) {
            BotEvents.log(context, "Chọn Deck ${wanted + 1}; chờ dấu tích đỏ xác nhận")
            if (dryRun) { Thread.sleep(500); return }
            tap(768 to BangChienVision.DECK_ROW_Y[wanted], frame)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1_500) {
                Thread.sleep(100)
                val bitmap = capture() ?: continue
                val current = vision.prepare(bitmap)
                val nowSelected = current.selectedDeck(available)
                if (nowSelected == wanted) {
                    BotEvents.log(context, "Đã thấy dấu tích đỏ ở Deck ${wanted + 1}")
                    tapWait(1492 to 933, current, capture, 5_000)
                    current.close()
                    battleStartedAt = System.currentTimeMillis()
                    return
                }
                current.close()
            }
            BotEvents.log(context, "Chưa thấy dấu tích đỏ; vòng sau sẽ kiểm tra lại")
            return
        }
        BotEvents.log(context, "Deck ${wanted + 1} được xác nhận/đã qua số lần thử -> bấm Tấn công")
        tapWait(1492 to 933, frame, capture, 5_000)
        battleStartedAt = System.currentTimeMillis()
    }

    private fun onBattle(frame: BangChienVision.Frame) {
        if (lastState != BangChienVision.State.BATTLE) {
            battles++
            prefs.edit().putInt("battles", battles).apply()
            battleStartedAt = System.currentTimeMillis()
            BotEvents.log(context, "Bắt đầu trận #$battles")
        }
        if (!dryRun && BotAccessibilityService.pendingCount() <= BATTLE_MAX_INFLIGHT_BATCHES * BATTLE_TAP_ROUNDS * BATTLE_CARDS.size) {
            val points = buildList<Pair<Int, Int>> {
                repeat(BATTLE_TAP_ROUNDS) { addAll(BATTLE_CARDS) }
            }.map { scalePoint(it, frame) }
            BotAccessibilityService.tapSequence(points)
        }
        if (battleStartedAt > 0 && System.currentTimeMillis() - battleStartedAt > 240_000 && sameCount % 40 == 0) {
            BotEvents.log(context, "Trận kéo dài hơn 4 phút; vẫn tiếp tục thao tác")
        }
    }

    private fun onResult(frame: BangChienVision.Frame, capture: () -> Bitmap?) {
        if (totalHouses == 1) {
            BotEvents.log(context, "Bang chỉ có 1 nhà (0/1) -> bấm Lobby")
            tapWait(946 to 994, frame, capture, 4_000)
        } else {
            BotEvents.log(context, "Màn kết quả -> bấm Tiếp tục")
            tapWait(1558 to 994, frame, capture, 4_000)
        }
    }

    private fun tapWait(point: Pair<Int, Int>, frame: BangChienVision.Frame, capture: () -> Bitmap?, timeoutMs: Long): Boolean {
        if (dryRun) { Thread.sleep(500); return false }
        val before = frame.smallSignature()
        tap(point, frame)
        val start = System.currentTimeMillis()
        var changed = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(100)
            val bitmap = capture() ?: continue
            val current = vision.prepare(bitmap)
            val signature = current.smallSignature()
            var diff = 0.0
            for (i in before.indices) diff += abs(signature[i] - before[i])
            diff /= before.size
            if (diff >= CHANGE_THRESHOLD) changed = true
            if (changed) {
                val (newState, _) = current.identify()
                if (newState != BangChienVision.State.UNKNOWN) {
                    cachedFrame?.close()
                    cachedFrame = current
                    return true
                }
                Thread.sleep(SETTLE_MS)
            }
            current.close()
        }
        BotEvents.log(context, "Đã chạm ${point.first},${point.second} nhưng chưa thấy màn hình đổi trong ${timeoutMs}ms")
        return false
    }

    private fun tap(point: Pair<Int, Int>, frame: BangChienVision.Frame) {
        if (dryRun) return
        val scaled = scalePoint(point, frame)
        BotAccessibilityService.tapSequence(listOf(scaled))
    }

    private fun scalePoint(point: Pair<Int, Int>, frame: BangChienVision.Frame): Pair<Int, Int> =
        (point.first * frame.displayWidth / BangChienVision.REF_W) to (point.second * frame.displayHeight / BangChienVision.REF_H)

    private fun cellCenter(index: Int): Pair<Int, Int> {
        val row = (index - 1) / 5
        val col = (index - 1) % 5
        val top = 210 + 130.4 * row
        val left = (if (row % 2 == 0) 471 else 298) + 349.8 * col
        return (left + 169).toInt() to (top + 59).toInt()
    }

    private fun saveUnknown(bitmap: Bitmap) {
        if (savedDebug >= 30) return
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "unknown_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(CompressFormat.PNG, 100, it) }
            savedDebug++
            BotEvents.log(context, "Đã lưu ảnh debug: ${file.absolutePath}")
        } catch (e: Exception) {
            BotEvents.log(context, "Không lưu được ảnh debug: ${e.message}")
        }
    }

    override fun close() {
        cachedFrame?.close(); cachedFrame = null
        vision.close()
    }
}
