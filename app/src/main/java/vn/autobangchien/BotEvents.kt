package vn.autobangchien

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BotEvents {
    const val ACTION_LOG = "vn.autobangchien.ACTION_LOG"
    private const val PREFS = "bot"

    fun log(context: Context, message: String, status: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$stamp  $message"
        val old = prefs.getString("logs", "") ?: ""
        val lines = (old.lines().filter { it.isNotBlank() } + line).takeLast(100)
        prefs.edit().putString("logs", lines.joinToString("\n"))
            .putString("last_status", status ?: prefs.getString("last_status", "● SẴN SÀNG"))
            .apply()
        context.sendBroadcast(Intent(ACTION_LOG).setPackage(context.packageName)
            .putExtra("message", line).putExtra("status", status))
        android.util.Log.i("AutoBangChien", message)
    }

    fun setStatus(context: Context, status: String, message: String? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_status", status).apply()
        context.sendBroadcast(Intent(ACTION_LOG).setPackage(context.packageName)
            .putExtra("status", status).putExtra("message", message))
        android.util.Log.i("AutoBangChien", message ?: status)
    }
}
