package vn.autobangchien

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var maxInput: EditText
    private val requestCapture = 501
    private val ui = Handler(Looper.getMainLooper())
    private var lastLog = ""
    private val prefs by lazy { getSharedPreferences("bot", Context.MODE_PRIVATE) }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun makeButton(text: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { action() }
        return b
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 32)
        window.navigationBarColor = Color.rgb(16, 24, 32)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(16), dp(14), dp(12))
        root.setBackgroundColor(Color.rgb(16, 24, 32))

        root.addView(label("AUTO BANG CHIẾN", 24f, 0xFF55D6A0.toInt()))
        status = label(BotLog.status, 18f, 0xFFFFC857.toInt())
        root.addView(status)
        stats = label("", 14f, 0xFFB8C4CE.toInt())
        root.addView(stats)

        maxInput = EditText(this)
        maxInput.hint = "Giới hạn trận (0 = không giới hạn)"
        maxInput.setHintTextColor(0xFF9AA7B2.toInt())
        maxInput.setTextColor(Color.WHITE)
        maxInput.inputType = InputType.TYPE_CLASS_NUMBER
        maxInput.setText(prefs.getInt("max", 0).toString())
        root.addView(maxInput, wrap())

        root.addView(makeButton("▶  BẮT ĐẦU") { startBot() }, wrap())
        root.addView(makeButton("■  DỪNG") { stopBot() }, wrap())
        root.addView(makeButton("CHỤP ẢNH GAME (sau 6 giây) - để gửi hỗ trợ") {
            val i = Intent(this, CaptureBotService::class.java)
            i.action = "SNAP"
            startService(i)
        }, wrap())
        root.addView(makeButton("CẤP QUYỀN TRỢ NĂNG") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, wrap())
        root.addView(makeButton("MỞ CÀI ĐẶT ỨNG DỤNG (nếu nút Trợ năng bị mờ)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName))
            )
        }, wrap())

        root.addView(label("NHẬT KÝ", 13f, 0xFF55D6A0.toInt()))
        scroll = ScrollView(this)
        logView = label(
            "1. Bật Trợ năng cho Auto Bang Chiến (nếu nút bị mờ: bấm nút 'Mở cài đặt ứng dụng', " +
                "chọn dấu ba chấm > Cho phép cài đặt bị hạn chế).\n" +
                "2. Bấm BẮT ĐẦU và chọn chia sẻ 'Toàn bộ màn hình'.\n" +
                "3. Mở game, xoay ngang, vào màn Bang chiến.",
            13f, 0xFFB8C4CE.toInt()
        )
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        status.text = BotLog.status
        status.setTextColor(if (BotLog.status.contains("ĐANG CHẠY")) 0xFF55D6A0.toInt() else 0xFFFFC857.toInt())
        val acc = if (BotAccessibilityService.isReady) "Trợ năng: BẬT" else "Trợ năng: CHƯA BẬT"
        stats.text = "Trận đã đánh: " + BotLog.battles + "     Bang mới: " + BotLog.guilds + "\n" + acc
        val t = BotLog.text()
        if (t.isNotEmpty() && t != lastLog) {
            lastLog = t
            logView.text = t
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startBot() {
        prefs.edit().putInt("max", maxInput.text.toString().toIntOrNull() ?: 0).apply()
        if (!BotAccessibilityService.isReady) {
            BotLog.e("Chưa bật Trợ năng. Hãy bật 'Auto Bang Chiến' trong Cài đặt > Trợ năng, rồi quay lại bấm BẮT ĐẦU.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
    }

    private fun stopBot() {
        stopService(Intent(this, CaptureBotService::class.java))
        BotLog.status = "● ĐÃ DỪNG"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode == RESULT_OK && data != null) {
            BotLog.clear()
            val i = Intent(this, CaptureBotService::class.java)
            i.putExtra("resultCode", resultCode)
            i.putExtra("projectionData", data)
            startForegroundService(i)
            BotLog.status = "● ĐANG KHỞI ĐỘNG"
            BotLog.i("Đã cấp quyền chụp màn hình. Hãy chuyển sang game (xoay ngang).")
        } else {
            BotLog.e("Bạn đã từ chối chia sẻ màn hình nên bot không chạy được.")
        }
    }
}
