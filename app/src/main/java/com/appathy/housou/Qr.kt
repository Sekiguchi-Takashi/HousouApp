package com.appathy.housou

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/** 端末登録用QRの生成と解釈 */
object Qr {

    const val PREFIX = "housou://dev?"

    fun payload(store: Store): String {
        val o = JSONObject()
        o.put("id", store.deviceId)
        o.put("name", store.termName)
        o.put("floor", store.termFloor)
        o.put("group", store.termGroup)
        o.put("ip", Net.localIp())
        o.put("port", Proto.PORT_CTRL)
        o.put("ver", Proto.APP_VER)
        return PREFIX + o.toString()
    }

    fun parse(text: String): JSONObject? {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return null
        return try {
            JSONObject(t.substring(PREFIX.length))
        } catch (e: Exception) {
            null
        }
    }

    fun encode(text: String, size: Int): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = m.width
            val h = m.height
            val px = IntArray(w * h)
            var y = 0
            while (y < h) {
                val off = y * w
                var x = 0
                while (x < w) {
                    px[off + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
                    x++
                }
                y++
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
