package vn.autobangchien

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** Runs tap batches sequentially, like the persistent adb shell input-tap queue in the PC tool. */
class BotAccessibilityService : AccessibilityService() {
    private data class Tap(val x: Int, val y: Int)
    private val queue = ArrayDeque<Tap>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var dispatching = false

    override fun onServiceConnected() {
        instance = this
        isReady = true
        BotEvents.setStatus(this, "● TRỢ NĂNG ĐÃ KẾT NỐI", "Dịch vụ Trợ năng đã sẵn sàng")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun enqueueTaps(points: List<Pair<Int, Int>>) {
        synchronized(queue) {
            points.forEach { queue.addLast(Tap(it.first, it.second)) }
        }
        mainHandler.post { dispatchNext() }
    }

    fun pendingTapCount(): Int = synchronized(queue) { queue.size + if (dispatching) 1 else 0 }

    /** Drops taps that have not started. The one currently being dispatched is at most 65 ms. */
    fun abortPendingTaps() {
        synchronized(queue) { queue.clear() }
    }

    private fun dispatchNext() {
        if (!isReady || dispatching) return
        val next = synchronized(queue) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return
        val path = Path().apply { moveTo(next.x.toFloat(), next.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
            .build()
        dispatching = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                dispatching = false
                dispatchNext()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                dispatching = false
                dispatchNext()
            }
        }, mainHandler)
        if (!accepted) {
            dispatching = false
            mainHandler.postDelayed({ dispatchNext() }, 100)
        }
    }

    override fun onDestroy() {
        synchronized(queue) { queue.clear() }
        if (instance === this) instance = null
        isReady = false
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: BotAccessibilityService? = null
        @Volatile var isReady: Boolean = false

        fun tapScreen(x: Int, y: Int) { instance?.enqueueTaps(listOf(x to y)) }
        fun tapSequence(points: List<Pair<Int, Int>>) { instance?.enqueueTaps(points) }
        fun pendingCount(): Int = instance?.pendingTapCount() ?: 0
        fun abortPending() { instance?.abortPendingTaps() }
    }
}
