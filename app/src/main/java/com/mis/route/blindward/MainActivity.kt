package com.ihateandroid.blindward

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ihateandroid.blindward.databinding.ActivityMainBinding
import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.mis.route.blindward.ClothingDetector
import com.mis.route.blindward.DetectionResult
import com.mis.route.myapp.CameraAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding get() = _binding!!
    private lateinit var permissionsRequestLauncher: ActivityResultLauncher<Array<String>>
    private var imageCapture: ImageCapture? = null

    private lateinit var clothingDetector: ClothingDetector
    private lateinit var cameraExecutor: ExecutorService

    private var frameCount = 0

    private var lastToastTime = 0L
    private val ToastCd = 5000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        clothingDetector = ClothingDetector(this)

        permissionsRequestLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val isCameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val isMicGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (isCameraGranted && isMicGranted) {
                startCamera()
            } else {
                // TODO: add dialog or bigger popup [?]
                Toast.makeText(
                    this,
                    "Пожалуйста, предоставьте разрешения в настройках. Это необходимо для работы приложения",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        checkAndRequestPermissions()
        cameraExecutor = Executors.newSingleThreadExecutor()
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



    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val cameraAnalyzer = CameraAnalyzer(
                clothingDetector = clothingDetector,
                onDetectionResult = ::handleDetections
            )

            imageAnalysis.setAnalyzer(cameraExecutor, cameraAnalyzer)
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)


            try {
                // привязка к lifecycle
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

            } catch (exc: Exception) {
                Log.e("TAG", "Ошибка запуска камеры", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleDetections(detections: List<DetectionResult>) {
        frameCount++

        // проверка задержки
        if (System.currentTimeMillis() - lastToastTime < ToastCd) {
            return
        }

        runOnUiThread {
            when {
                detections.isEmpty() -> {
                    Toast.makeText(this@MainActivity, "Nothing", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val mainDetection = detections.maxByOrNull { it.confidence }
                    mainDetection?.let { detection ->
                        showDetectionToast(detection, detections.size)
                    }
                }
            }
            lastToastTime = System.currentTimeMillis()
        }
    }
    private fun showDetectionToast(detection: DetectionResult, totalDetections: Int) {
        val className = clothingDetector.getClassName(detection.classId)
        val confidencePercent = (detection.confidence * 100).toInt()

        val toastMessage = if (totalDetections > 1) {
            "$className ($confidencePercent%) + ещё ${totalDetections - 1}"
        } else {
            " $className ($confidencePercent%)"
        }

        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Обнаружен: $toastMessage")
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
        cameraExecutor.shutdown()
    }
}