package com.avf.qrjson

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.avf.qrjson.databinding.ActivityMainBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Простое приложение: сканирует один QR-код камерой, показывает его содержимое
 * на экране в виде отформатированного JSON (если содержимое — валидный JSON,
 * оно просто красиво форматируется; если нет — оборачивается в {"raw_text": "..."},
 * чтобы на экране всегда был корректный JSON).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null

    // Пока true — анализатор кадров активно ищет QR. После первой успешной
    // находки ставим false, чтобы не долбить декодером один и тот же результат.
    private val scanning = AtomicBoolean(true)

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnScanAgain.setOnClickListener {
            binding.resultPanel.visibility = android.view.View.GONE
            binding.previewView.visibility = android.view.View.VISIBLE
            binding.hintText.visibility = android.view.View.VISIBLE
            scanning.set(true)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ::analyzeFrame)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось запустить камеру: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!scanning.get()) {
            imageProxy.close()
            return
        }
        try {
            val text = decodeQr(imageProxy)
            if (text != null && scanning.compareAndSet(true, false)) {
                val formatted = toFormattedJson(text)
                runOnUiThread { showResult(formatted) }
            }
        } finally {
            imageProxy.close()
        }
    }

    /** Достаёт Y-плоскость кадра (яркость), убирает паддинг rowStride и поворачивает
     * под ориентацию экрана, затем пробует найти в ней QR-код через ZXing. */
    private fun decodeQr(imageProxy: ImageProxy): String? {
        val width = imageProxy.width
        val height = imageProxy.height
        val plane = imageProxy.planes[0]
        val rowStride = plane.rowStride
        val buffer = plane.buffer

        val packed = ByteArray(width * height)
        val rowBytes = ByteArray(rowStride)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            val toRead = minOf(rowStride, buffer.remaining())
            buffer.get(rowBytes, 0, toRead)
            System.arraycopy(rowBytes, 0, packed, row * width, width)
        }

        val (rotated, rWidth, rHeight) = when (imageProxy.imageInfo.rotationDegrees) {
            90 -> Triple(rotate90(packed, width, height), height, width)
            180 -> Triple(packed.reversedArray(), width, height)
            270 -> Triple(rotate270(packed, width, height), height, width)
            else -> Triple(packed, width, height)
        }

        val source = PlanarYUVLuminanceSource(
            rotated, rWidth, rHeight, 0, 0, rWidth, rHeight, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        return try {
            reader.decodeWithState(bitmap).text
        } catch (e: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotate90(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                out[i++] = data[y * width + x]
            }
        }
        return out
    }

    private fun rotate270(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                out[i++] = data[y * width + x]
            }
        }
        return out
    }

    /** Пытается распарсить строку как JSON-объект/массив и красиво отформатировать.
     * Если это не валидный JSON — оборачивает исходный текст в {"raw_text": "..."},
     * чтобы на экране всегда отображался корректный отформатированный JSON. */
    private fun toFormattedJson(text: String): String {
        val trimmed = text.trim()
        return try {
            JSONObject(trimmed).toString(4)
        } catch (e: Exception) {
            try {
                JSONArray(trimmed).toString(4)
            } catch (e2: Exception) {
                JSONObject().put("raw_text", text).toString(4)
            }
        }
    }

    private fun showResult(formattedJson: String) {
        binding.resultText.text = formattedJson
        binding.previewView.visibility = android.view.View.GONE
        binding.hintText.visibility = android.view.View.GONE
        binding.resultPanel.visibility = android.view.View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
