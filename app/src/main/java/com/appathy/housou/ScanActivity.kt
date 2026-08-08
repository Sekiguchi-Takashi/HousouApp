package com.appathy.housou

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 端末QRの読み取り。成功すると "text" を返して終了する */
class ScanActivity : AppCompatActivity() {

    private var exec: ExecutorService? = null
    private val reader = MultiFormatReader()
    private var pv: PreviewView? = null
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        reader.setHints(hints)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val pv = PreviewView(this)
        this.pv = pv
        root.addView(pv, FrameLayout.LayoutParams(Ui.MP, Ui.MP))

        val tip = Ui.tv(this, "端末画面のQRコードを枠内に映してください", 14f, Ui.FG, true)
        tip.gravity = Gravity.CENTER
        tip.setBackgroundColor(0xCC000000.toInt())
        tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14))
        val lp = FrameLayout.LayoutParams(Ui.MP, Ui.WC)
        lp.gravity = Gravity.BOTTOM
        root.addView(tip, lp)
        setContentView(root)

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 55)
        } else {
            start(pv)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 55) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val p = pv
            if (ok && p != null) start(p) else finish()
        }
    }

    private fun start(pv: PreviewView) {
        val e = Executors.newSingleThreadExecutor()
        exec = e
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(pv.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(e) { img -> analyze(img) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (t: Throwable) {
                finish()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        if (done) {
            img.close()
            return
        }
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining())
            buf.get(data)
            val w = img.width
            val h = img.height
            val src = PlanarYUVLuminanceSource(data, plane.rowStride, h, 0, 0, w, h, false)
            val bmp = BinaryBitmap(HybridBinarizer(src))
            val res = reader.decodeWithState(bmp)
            val text = res.text
            if (!text.isNullOrEmpty()) {
                done = true
                runOnUiThread {
                    val i = android.content.Intent()
                    i.putExtra("text", text)
                    setResult(RESULT_OK, i)
                    finish()
                }
            }
        } catch (t: Throwable) {
            // 読み取れないフレームは無視
        } finally {
            reader.reset()
            img.close()
        }
    }

    override fun onDestroy() {
        try { exec?.shutdown() } catch (t: Throwable) { }
        super.onDestroy()
    }
}
