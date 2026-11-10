package vn.autobangchien

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/** MediaProjection screen capture foreground service. Requires fresh user approval each run. */
class CaptureBotService : Service() {
    companion object {
        const val ACTION_STOP = "vn.autobangchien.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL_ID = "bot_run"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var engine: BangChienEngine? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_NOT_STICKY
        try {
            startBotForeground()
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            if (resultCode != Activity.RESULT_OK || data == null) {
                BotEvents.setStatus(this, "● CHƯA CÓ QUYỀN CHỤP MÀN HÌNH", "Không nhận được quyền MediaProjection")
                stopSelf()
                return START_NOT_STICKY
            }

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            densityDpi = metrics.densityDpi
            if (screenWidth <= 0 || screenHeight <= 0) error("Kích thước màn hình không hợp lệ")

            reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (running.get()) {
                        running.set(false)
                        BotEvents.setStatus(this@CaptureBotService, "● ĐÃ MẤT QUYỀN CHỤP", "Android đã dừng phiên chia sẻ màn hình")
                        stopSelf()
                    }
                }
            }, mainHandler)
            display = projection?.createVirtualDisplay(
                "AutoBangChien", screenWidth, screenHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, mainHandler
            )
            engine = BangChienEngine(this)
            running.set(true)
            BotEvents.setStatus(this, "● ĐANG CHẠY", "Đã bật chụp màn hình. Giữ game ở foreground, chế độ ngang.")
            BotEvents.log(this, "MediaProjection hoạt động: ${screenWidth}x${screenHeight}")
            worker = Thread({ runLoop() }, "AutoBangChien-Worker").also { it.start() }
        } catch (e: Exception) {
            BotEvents.setStatus(this, "● KHỞI ĐỘNG THẤT BẠI", "Không khởi động được bot: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runLoop() {
        try {
            while (running.get()) {
                val activeEngine = engine ?: break
                if (activeEngine.hasCachedFrame()) {
                    if (!activeEngine.tick(null, ::captureFrame)) break
                } else {
                    val bitmap = captureFrame()
                    if (bitmap == null) {
                        Thread.sleep(100)
                        continue
                    }
                    if (!activeEngine.tick(bitmap, ::captureFrame)) break
                }
                Thread.sleep(900)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            BotEvents.setStatus(this, "● BOT GẶP LỖI", "Vòng chạy dừng do lỗi: ${e.message}")
            android.util.Log.e("AutoBangChien", "Capture worker failed", e)
        } finally {
            running.set(false)
            mainHandler.post { stopSelf() }
        }
    }

    /** Copies the newest ImageReader frame and always closes the Image promptly. */
    private fun captureFrame(): Bitmap? {
        var image: Image? = null
        return try {
            image = reader?.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val result = if (paddedWidth == screenWidth) padded else Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
            if (result !== padded) padded.recycle()
            result
        } catch (e: Exception) {
            android.util.Log.w("AutoBangChien", "Could not read screen frame", e)
            null
        } finally {
            image?.close()
        }
    }

    private fun startBotForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Auto Bang Chiến", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val openApp = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this, 11, Intent(this, CaptureBotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = builder.setContentTitle("Auto Bang Chiến đang hoạt động")
            .setContentText("Đang nhận diện màn hình game")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Dừng bot", stopAction)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        running.set(false)
        BotAccessibilityService.abortPending()
        worker?.interrupt()
        try { worker?.join(1_500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        worker = null
        try { engine?.close() } catch (_: Exception) { }
        engine = null
        display?.release(); display = null
        reader?.close(); reader = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (getSharedPreferences("bot", Context.MODE_PRIVATE).getString("last_status", "") == "● ĐANG CHẠY") {
            BotEvents.setStatus(this, "● ĐÃ DỪNG", "Bot đã dừng")
        }
        super.onDestroy()
    }
}
