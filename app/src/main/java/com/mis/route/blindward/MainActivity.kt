package com.ihateandroid.blindward

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ihateandroid.blindward.databinding.ActivityMainBinding
import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.mis.route.blindward.ClothingDetector
import com.mis.route.blindward.DetectionResult
import com.mis.route.myapp.CameraAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding get() = _binding!!

    private lateinit var permissionsRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var clothingDetector: ClothingDetector
    private lateinit var cameraExecutor: ExecutorService

    private var frameCount = 0
    private var lastToastTime = 0L
    private val Detec_cd = 800L

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация детектора
        clothingDetector = ClothingDetector(this)

        // Настройка PreviewView
        binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER

        permissionsRequestLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val isCameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val isMicGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (isCameraGranted && isMicGranted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Пожалуйста, предоставьте разрешения в настройках",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        checkAndRequestPermissions()
        cameraExecutor = Executors.newSingleThreadExecutor()

        Log.d("MainActivity", "Activity создана")
    }

    private fun checkAndRequestPermissions() {
        val isCameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val isMicGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        when {
            isCameraGranted && isMicGranted -> {
                startCamera()
            }
            else -> {
                permissionsRequestLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        val cameraAnalyzer = CameraAnalyzer(
                            clothingDetector = clothingDetector,
                            onDetectionResult = ::handleDetections
                        )
                        analysis.setAnalyzer(cameraExecutor, cameraAnalyzer)
                    }

                bindCamera(cameraSelector, preview, imageAnalysis)

            } catch (exc: Exception) {
                Log.e("MainActivity", "Ошибка инициализации камеры", exc)
                Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(
        cameraSelector: CameraSelector,
        preview: Preview,
        imageAnalysis: ImageAnalysis
    ) {
        try {
            cameraProvider?.unbindAll()

            cameraProvider?.bindToLifecycle(
                this as LifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            Log.d("MainActivity", "Камера успешно запущена")
        } catch (exc: Exception) {
            Log.e("MainActivity", "Ошибка привязки камеры", exc)
        }
    }

    private fun handleDetections(detections: List<DetectionResult>) {
        frameCount++

        val currentTime = System.currentTimeMillis()

        if (detections.isNotEmpty() && currentTime - lastToastTime >= Detec_cd) {
            runOnUiThread {
                showDetectionToast(detections)
            }
            lastToastTime = currentTime
        }

        // Логирование детекций
        if (detections.isNotEmpty()) {
            Log.d("MainActivity", "Кадр $frameCount: ${detections.size} объектов")
            detections.forEach { detection ->
                Log.d("MainActivity",
                    "- ${clothingDetector.getClassName(detection.classId)}: " +
                            "${String.format("%.1f", detection.confidence * 100)}% " +
                            "[${detection.x1.toInt()},${detection.y1.toInt()},${detection.x2.toInt()},${detection.y2.toInt()}]"
                )
            }
        }
    }

    private fun showDetectionToast(detections: List<DetectionResult>) {
        val mainDetection = detections.maxByOrNull { it.confidence }
        mainDetection?.let { detection ->
            val className = clothingDetector.getClassName(detection.classId)
            val confidencePercent = (detection.confidence * 100).toInt()

            val toastMessage = if (detections.size > 1) {
                "$className ($confidencePercent%) + ещё ${detections.size - 1}"
            } else {
                "$className ($confidencePercent%)"
            }

            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Показан тост: $toastMessage")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        // Перезапускаем камеру если разрешения есть
        if (allPermissionsGranted() && cameraProvider == null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
        // Остановка анализа для экономии энергии
        imageAnalysis?.clearAnalyzer()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")

        imageAnalysis?.clearAnalyzer()
        cameraExecutor.shutdown()
        clothingDetector.clean()
        _binding = null
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}