package com.ihateandroid.blindward

// базовые компоненты Android Framework
import android.content.pm.PackageManager
import android.Manifest
import android.os.Bundle

import android.util.Log
import android.widget.Toast

// AndroidX
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// View
import com.ihateandroid.blindward.databinding.ActivityMainBinding

// TTS
import android.speech.tts.TextToSpeech
import java.util.Locale

// Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview

// жизненный цикл CameraX(автоматическое управление камерой)
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding get() = _binding!!
    private lateinit var permissionsRequestLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var textToSpeech: TextToSpeech
    private var isTTSInitialized = false

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

        initialTTS()

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

    private fun initialTTS() {
        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {
                isTTSInitialized = true

                val result = textToSpeech.setLanguage(Locale("ru", "RU"))

                // проверка поддержки языка
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Язык не поддерживается")
                }
            } else {
                Log.e("TTS", "Ошибка инициализации TTS")
            }
        }
    }
    private fun speechText(text: String) {
        // Проверкак инициализации
        if (isTTSInitialized) {
            // Озвучиваем текст
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            // Можно добавить сообщение пользователю
            Log.e("TTS" , "Ошибка speechText")
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
                    speechText("Ничего")
                    Toast.makeText(this@MainActivity, "Ничего", Toast.LENGTH_SHORT).show()
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
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        _binding = null
        cameraExecutor.shutdown()
    }
}