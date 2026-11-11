package vn.autobangchien

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nhật ký và trạng thái dùng chung giữa bot và màn hình chính. */
object BotLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var status: String = "● CHƯA CHẠY"
    @Volatile var battles: Int = 0
    @Volatile var guilds: Int = 0

    fun i(msg: String) {
        add(msg)
        Log.i("ABC", msg)
    }

    fun e(msg: String) {
        add("⚠ " + msg)
        Log.e("ABC", msg)
    }

    private fun add(msg: String) {
        val stamp = synchronized(fmt) { fmt.format(Date()) }
        synchronized(lines) {
            lines.add(stamp + "  " + msg)
            while (lines.size > 150) {
                lines.removeAt(0)
            }
        }
    }

    fun text(): String {
        synchronized(lines) {
            val start = if (lines.size > 60) lines.size - 60 else 0
            return lines.subList(start, lines.size).joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
        }
        battles = 0
        guilds = 0
    }
}
