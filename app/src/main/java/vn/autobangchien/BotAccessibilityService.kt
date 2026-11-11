package vn.autobangchien

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bấm vào màn hình. Mỗi lần bấm chạy lần lượt trong một hàng đợi và CHỜ cử chỉ trước hoàn tất
 * rồi mới gửi cử chỉ sau (gửi dồn thì Android huỷ cử chỉ trước, chỉ cử chỉ cuối được thực hiện).
 */
class BotAccessibilityService : AccessibilityService() {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private val pending = AtomicInteger(0)

    override fun onServiceConnected() {
        instance = this
        isReady = true
        BotLog.i("Dịch vụ Trợ năng đã kết nối.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Đưa một lần bấm vào hàng đợi (toạ độ theo pixel màn hình thật). */
    fun enqueueTap(x: Int, y: Int) {
        pending.incrementAndGet()
        val gen = generation.get()
        exec.execute { runTap(x, y, gen) }
    }

    private fun runTap(x: Int, y: Int, gen: Int) {
        try {
            if (gen == generation.get()) {
                tapBlocking(x, y)
            }
        } catch (e: Exception) {
            // bỏ qua lần bấm lỗi
        } finally {
            pending.decrementAndGet()
        }
    }

    /** Số lần bấm đang chờ trong hàng đợi. */
    fun pendingTaps(): Int = pending.get()

    /** Huỷ các lần bấm chưa thực hiện (dùng khi hết trận để không bấm lạc sang màn kết quả). */
    fun abortQueued() {
        generation.incrementAndGet()
    }

    private fun tapBlocking(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val ok = dispatchGesture(gesture, cb, null)
        if (!ok) return
        latch.await(600, TimeUnit.MILLISECONDS)
        Thread.sleep(15)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        isReady = false
        exec.shutdownNow()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: BotAccessibilityService? = null
        @Volatile var isReady: Boolean = false
    }
}
