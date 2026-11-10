package vn.autobangchien

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var maxInput: EditText
    private lateinit var idleInput: EditText
    private lateinit var dryRunCheck: CheckBox
    private val requestCapture = 501

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            intent.getStringExtra("status")?.let { statusView.text = it }
            refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 32)
        window.navigationBarColor = Color.rgb(16, 24, 32)
        val prefs = getSharedPreferences("bot", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, 8, 0, 8)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
        }
        root.addView(text("AUTO BANG CHIẾN", 25f, 0xFF55D6A0.toInt()))
        root.addView(text("Bản Android độc lập • Xoay máy ngang trước khi Bắt đầu", 12f, 0xFFB8C4CE.toInt()))
        statusView = text(prefs.getString("last_status", "● SẴN SÀNG") ?: "● SẴN SÀNG", 18f, 0xFFFFC857.toInt())
        root.addView(statusView)
        statsView = text("Trận: ${prefs.getInt("battles", 0)}     Bang mới: ${prefs.getInt("guilds", 0)}", 13f, 0xFFB8C4CE.toInt())
        root.addView(statsView)

        root.addView(text("CẤU HÌNH", 14f, 0xFF55D6A0.toInt()))
        maxInput = EditText(this).apply {
            hint = "Giới hạn trận (0 = không giới hạn)"
            setHintTextColor(0xFF9AA7B2.toInt()); setTextColor(Color.WHITE)
            inputType = 2; setText(prefs.getInt("max", 0).toString())
        }
        root.addView(maxInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        idleInput = EditText(this).apply {
            hint = "Thời gian nghỉ khi hết mục tiêu (giây, 1–30)"
            setHintTextColor(0xFF9AA7B2.toInt()); setTextColor(Color.WHITE)
            inputType = 2; setText(prefs.getInt("idle_wait", 3).toString())
        }
        root.addView(idleInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dryRunCheck = CheckBox(this).apply {
            text = "Chạy thử (chỉ nhận diện, KHÔNG chạm)"
            setTextColor(0xFFFFC857.toInt()); isChecked = prefs.getBoolean("dry_run", false)
        }
        root.addView(dryRunCheck)

        root.addView(button("▶  BẮT ĐẦU", ::startBot))
        root.addView(button("■  DỪNG", ::stopBot))
        root.addView(button("CẤP QUYỀN TRỢ NĂNG", { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }))
        root.addView(text("NHẬT KÝ", 14f, 0xFF55D6A0.toInt()))
        logView = text("", 12f, 0xFFB8C4CE.toInt()).apply { setTextIsSelectable(true) }
        val logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (170 * resources.displayMetrics.density).toInt()))
        val page = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(page)
        refreshLog()
    }

    private fun startBot() {
        val maxBattles = maxInput.text.toString().toIntOrNull()
        if (maxBattles == null || maxBattles < 0) {
            statusView.text = "Giới hạn trận phải là số >= 0"; return
        }
        val idle = idleInput.text.toString().toIntOrNull()
        if (idle == null || idle !in 1..30) {
            statusView.text = "Thời gian nghỉ phải từ 1 đến 30 giây"; return
        }
        getSharedPreferences("bot", Context.MODE_PRIVATE).edit()
            .putInt("max", maxBattles).putInt("idle_wait", idle)
            .putBoolean("dry_run", dryRunCheck.isChecked)
            .putInt("battles", 0).putInt("guilds", 0)
            .putString("logs", "").apply()

        if (!BotAccessibilityService.isReady) {
            statusView.text = "Hãy bật quyền Trợ năng trước"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
    }

    @Deprecated("Activity result API kept simple for compatibility with this starter project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureBotService::class.java).apply {
                putExtra(CaptureBotService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureBotService.EXTRA_PROJECTION_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            statusView.text = "● ĐANG KHỞI ĐỘNG"
            BotEvents.log(this, "Người dùng đã cấp quyền chia sẻ màn hình")
        } else {
            statusView.text = "● CHƯA CẤP QUYỀN CHỤP MÀN HÌNH"
            BotEvents.log(this, "Người dùng từ chối quyền chia sẻ màn hình")
        }
    }

    private fun stopBot() {
        stopService(Intent(this, CaptureBotService::class.java))
        statusView.text = "● ĐÃ DỪNG"
        BotEvents.setStatus(this, "● ĐÃ DỪNG", "Đã yêu cầu dừng bot")
    }

    private fun refreshLog() {
        val prefs = getSharedPreferences("bot", Context.MODE_PRIVATE)
        logView.text = prefs.getString("logs", "Chưa có nhật ký") ?: "Chưa có nhật ký"
        statsView.text = "Trận: ${prefs.getInt("battles", 0)}     Bang mới: ${prefs.getInt("guilds", 0)}"
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BotEvents.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
        val prefs = getSharedPreferences("bot", Context.MODE_PRIVATE)
        statusView.text = prefs.getString("last_status", "● SẴN SÀNG") ?: "● SẴN SÀNG"
        refreshLog()
    }

    override fun onPause() {
        try { unregisterReceiver(logReceiver) } catch (_: Exception) { }
        super.onPause()
    }
}
