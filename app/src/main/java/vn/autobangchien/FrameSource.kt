package vn.autobangchien

import android.media.Image
import android.media.ImageReader
import java.nio.ByteOrder

/**
 * Lấy khung hình mới nhất từ ImageReader. Màn hình tĩnh thì không có khung hình mới,
 * khi đó trả lại khung hình gần nhất (vì màn hình không đổi).
 */
class FrameSource(private val reader: ImageReader, initialW: Int, initialH: Int) {
    @Volatile var w: Int = initialW
        private set
    @Volatile var h: Int = initialH
        private set
    @Volatile private var lastFrame: Frame? = null

    /** Ánh xạ tọa độ logic bằng đúng vùng ảnh mà Vision đang xử lý. */
    fun toScreen(p: Pt): Pt = lastFrame?.toScreen(p)
        ?: Pt(p.x * w / Cfg.REF_W, p.y * h / Cfg.REF_H)

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
                val iw = img.width
                val ih = img.height
                w = iw
                h = ih
                val plane = img.planes[0]
                val ps = plane.pixelStride
                val rs = plane.rowStride
                if (ps == 4) {
                    val ib = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                    val rowInts = rs / 4
                    val px = IntArray(iw * ih)
                    for (y in 0 until ih) {
                        ib.position(y * rowInts)
                        ib.get(px, y * iw, iw)
                    }
                    // Bộ nhớ là R,G,B,A -> đổi sang ARGB
                    for (i in px.indices) {
                        val c = px[i]
                        px[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c shr 16) and 0xFF)
                    }
                    lastFrame = Frame(iw, ih, px)
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
