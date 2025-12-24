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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding get() = _binding!!

    private lateinit var permissionsRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var clothingDetector: ClothingDetector
    private lateinit var cameraExecutor: ExecutorService

    // Text-to-Speech
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false // Флаг для отслеживания процесса озвучивания

    private var frameCount = 0
    private var lastToastTime = 0L
    private var lastSpeechTime = 0L

    // Увеличенный интервал между распознаваниями (3 секунды)
    // Это дает достаточно времени для полного проговаривания текста
    private val Detec_cd = 3000L

    // Минимальный интервал между озвучиваниями (2.5 секунды)
    private val Speech_cd = 2500L

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация Text-to-Speech
        tts = TextToSpeech(this, this)

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

        Log.d(TAG, "Activity создана")
    }

    // Callback для инициализации TTS
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Русский язык не поддерживается TTS")
                Toast.makeText(this, "Голосовой вывод на русском языке недоступен", Toast.LENGTH_LONG).show()
                isTtsReady = false
            } else {
                // Настройки голоса
                tts?.setPitch(1.0f) // Нормальная высота голоса
                tts?.setSpeechRate(0.95f) // Немного медленнее для лучшего понимания

                // Настройка слушателя для отслеживания состояния озвучивания
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        Log.d(TAG, "Начало озвучивания: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        Log.d(TAG, "Озвучивание завершено: $utteranceId")
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        Log.e(TAG, "Ошибка озвучивания: $utteranceId")
                    }
                })

                isTtsReady = true
                Log.d(TAG, "TTS успешно инициализирован")

                // Приветственное сообщение
                speak("Приложение для распознавания одежды запущено")
            }
        } else {
            Log.e(TAG, "Ошибка инициализации TTS")
            isTtsReady = false
        }
    }

    // Функция для озвучивания текста с контролем состояния
    private fun speak(text: String) {
        if (isTtsReady && tts != null) {
            val currentTime = System.currentTimeMillis()

            // Проверяем, прошло ли достаточно времени с последнего озвучивания
            if (currentTime - lastSpeechTime >= Speech_cd || lastSpeechTime == 0L) {
                // Останавливаем предыдущее озвучивание если оно еще идет
                if (isSpeaking) {
                    tts?.stop()
                }

                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, currentTime.toString())

                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, currentTime.toString())
                lastSpeechTime = currentTime
                Log.d(TAG, "Озвучивание: $text")
            } else {
                Log.d(TAG, "Озвучивание пропущено (слишком рано): $text")
            }
        }
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
                    .setTargetResolution(Size(736, 736))
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
                Log.e(TAG, "Ошибка инициализации камеры", exc)
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

            Log.d(TAG, "Камера успешно запущена")
        } catch (exc: Exception) {
            Log.e(TAG, "Ошибка привязки камеры", exc)
        }
    }

    private fun handleDetections(detections: List<DetectionResult>) {
        frameCount++

        val currentTime = System.currentTimeMillis()

        // Проверяем:
        // 1. Есть ли обнаруженные объекты
        // 2. Прошло ли достаточно времени с последнего распознавания
        // 3. НЕ идет ли сейчас озвучивание (важно!)
        if (detections.isNotEmpty() &&
            currentTime - lastToastTime >= Detec_cd &&
            !isSpeaking) {

            runOnUiThread {
                showDetectionToast(detections)
            }
            lastToastTime = currentTime
        }

        // Логирование детекций
        if (detections.isNotEmpty()) {
            Log.d(TAG, "Кадр $frameCount: ${'$'}{detections.size} объектов")
            detections.forEach { detection ->
                Log.d(TAG,
                    "")
            }
        }
    }

    private fun showDetectionToast(detections: List<DetectionResult>) {
        val mainDetection = detections.maxByOrNull { it.confidence }
        mainDetection?.let { detection ->
            val className = clothingDetector.getClassName(detection.classId)
            val confidencePercent = (detection.confidence * 100).toInt()

            val toastMessage = if (detections.size > 1) {
                "$className ($confidencePercent%) + ещё ${'$'}{detections.size - 1}"
            } else {
                "$className ($confidencePercent%)"
            }

            // Показываем Toast
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Показан тост: $toastMessage")

            // Озвучиваем обнаруженную одежду
            val voiceMessage = getVoiceMessage(className, detections.size)
            speak(voiceMessage)
        }
    }

    // Формирование голосового сообщения с правильным склонением
    private fun getVoiceMessage(className: String, totalDetections: Int): String {
        // Переводим названия классов в именительный падеж для озвучивания
        val russianClassName = getRussianClassName(className)

        return if (totalDetections > 1) {
            "Обнаружено: $russianClassName и ещё ${'$'}{totalDetections - 1} " +
                    getItemsWord(totalDetections - 1)
        } else {
            "Обнаружено: $russianClassName"
        }
    }

    // Перевод названий классов на русский язык
    private fun getRussianClassName(className: String): String {
        return when (className.lowercase()) {
            "t-shirt", "tshirt" -> "футболка"
            "shirt" -> "рубашка"
            "jogger" -> "штаны"
            "pants", "trousers" -> "брюки"
            "jacket", "coat" -> "куртка"
            "dress" -> "платье"
            "skirt" -> "юбка"
            "sweater", "pullover" -> "свитер"
            "hoodie" -> "толстовка"
            "short" -> "шорты"
            "jeans" -> "джинсы"
            "socks" -> "носки"
            "shoes" -> "обувь"
            "sneakers" -> "кроссовки"
            "boots" -> "ботинки"
            "hat", "cap" -> "головной убор"
            "scarf" -> "шарф"
            "gloves" -> "перчатки"
            "Jacket" -> "куртка"
            "Polo" -> "поло"
            else -> className // Если не найдено соответствие, озвучиваем как есть
        }
    }

    // Правильное склонение слова "предмет/предмета/предметов"
    private fun getItemsWord(count: Int): String {
        return when {
            count % 10 == 1 && count % 100 != 11 -> "предмет"
            count % 10 in 2..4 && (count % 100 < 10 || count % 100 >= 20) -> "предмета"
            else -> "предметов"
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // Перезапускаем камеру если разрешения есть
        if (allPermissionsGranted() && cameraProvider == null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        // Остановка анализа для экономии энергии
        imageAnalysis?.clearAnalyzer()

        // Останавливаем озвучивание при сворачивании
        tts?.stop()
        isSpeaking = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        imageAnalysis?.clearAnalyzer()
        cameraExecutor.shutdown()
        clothingDetector.clean()

        // Освобождаем ресурсы TTS
        tts?.stop()
        tts?.shutdown()

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