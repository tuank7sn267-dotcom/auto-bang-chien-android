package vn.autobangchien

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/** Dịch vụ nền: chụp màn hình (MediaProjection) và chạy bộ máy Bang chiến. */
class CaptureBotService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var engine: BangChienEngine? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = readProjectionData(intent)
        if (data == null || code != Activity.RESULT_OK) {
            BotLog.e("Không nhận được quyền chụp màn hình.")
            BotLog.status = "● LỖI"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (e: Exception) {
            BotLog.e("Không khởi động được chụp màn hình: " + e)
            BotLog.status = "● LỖI"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("projectionData", Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>("projectionData")
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(code: Int, data: Intent) {
        shutdown()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(code, data)
        projection = proj
        // Android 14+ BẮT BUỘC đăng ký callback trước khi bắt đầu chụp, nếu không sẽ bị lỗi ngay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                BotLog.i("Hệ thống đã dừng chia sẻ màn hình.")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        // Game chạy ngang: luôn chụp theo khung ngang (cạnh dài là chiều rộng).
        val cw = maxOf(metrics.widthPixels, metrics.heightPixels)
        val ch = minOf(metrics.widthPixels, metrics.heightPixels)
        val rd = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = rd
        display = proj.createVirtualDisplay(
            "AutoBangChien", cw, ch, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )
        val eng = BangChienEngine(this, Vision(assets), FrameSource(rd, cw, ch))
        engine = eng
        BotLog.status = "● ĐANG CHẠY"
        BotLog.i("Đã bắt đầu chụp màn hình (" + cw + "x" + ch + "). Hãy mở game ở chế độ ngang.")
        val t = Thread {
            try {
                eng.run()
            } catch (e: Exception) {
                BotLog.e("Lỗi bot: " + e)
            }
            stopSelf()
        }
        worker = t
        t.start()
    }

    private fun startForegroundCompat() {
        val channelId = "bot_run"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Auto Bang Chiến", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Auto Bang Chiến đang hoạt động")
            .setContentText("Đang nhận diện màn hình game")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, n)
        }
    }

    private fun shutdown() {
        engine?.requestStop()
        val w = worker
        if (w != null && w !== Thread.currentThread()) {
            try {
                w.join(1500)
            } catch (e: Exception) {
                // bỏ qua
            }
        }
        worker = null
        engine = null
        try { display?.release() } catch (e: Exception) { }
        display = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
    }

    override fun onDestroy() {
        shutdown()
        if (BotLog.status != "● LỖI") BotLog.status = "● ĐÃ DỪNG"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
