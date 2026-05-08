package com.offlinepay.wallet

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object Qr {
    fun render(text: String, size: Int = 600): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        return bmp
    }
}

class QrScanActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val pv = PreviewView(this); setContentView(pv)
        val providerFut = ProcessCameraProvider.getInstance(this)
        providerFut.addListener({
            val provider = providerFut.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val scanner = BarcodeScanning.getClient()
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                val img = proxy.image
                if (img != null) {
                    val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            val qr = barcodes.firstOrNull()?.rawValue
                            if (qr != null) {
                                val data = Intent().apply { putExtra("qr", qr) }
                                setResult(Activity.RESULT_OK, data); finish()
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } else proxy.close()
            }
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
        }, ContextCompat.getMainExecutor(this))
    }
}
