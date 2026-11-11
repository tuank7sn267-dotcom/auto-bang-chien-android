package vn.autobangchien

import android.media.Image
import android.media.ImageReader
import java.nio.ByteOrder

/**
 * Lấy khung hình mới nhất từ ImageReader. Màn hình tĩnh thì không có khung hình mới,
 * khi đó trả lại khung hình gần nhất (vì màn hình không đổi).
 */
class FrameSource(private val reader: ImageReader, val w: Int, val h: Int) {
    @Volatile private var lastFrame: Frame? = null

    private fun acquire(): Image? {
        return try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
    }

    fun grab(): Frame? {
        val img = acquire()
        if (img != null) {
            try {
                val plane = img.planes[0]
                val ps = plane.pixelStride
                val rs = plane.rowStride
                if (ps == 4) {
                    val ib = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                    val rowInts = rs / 4
                    val px = IntArray(w * h)
                    for (y in 0 until h) {
                        ib.position(y * rowInts)
                        ib.get(px, y * w, w)
                    }
                    // Bộ nhớ là R,G,B,A -> đổi sang ARGB
                    for (i in px.indices) {
                        val c = px[i]
                        px[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c shr 16) and 0xFF)
                    }
                    lastFrame = Frame(w, h, px)
                }
            } catch (e: Exception) {
                BotLog.e("Lỗi đọc ảnh màn hình: " + e.message)
            } finally {
                img.close()
            }
        }
        return lastFrame
    }
}
