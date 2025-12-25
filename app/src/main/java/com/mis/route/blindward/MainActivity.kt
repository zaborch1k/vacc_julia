package com.mis.route.blindward

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.mis.route.blindward.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.view.View
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.net.ssl.*
import java.security.cert.X509Certificate
import java.security.SecureRandom
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding get() = _binding!!

    private lateinit var permissionsRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var clothingDetector: ClothingDetector
    private lateinit var cameraExecutor: ExecutorService

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    private val client = createUnsafeOkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isRequestInProgress = false

    private var lastDetectedClothing = "одежда не распознана"

    private val GIGACHAT_CLIENT_ID = "secret"
    private val GIGACHAT_CLIENT_SECRET = "secret"

    private val GIGACHAT_AUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private val GIGACHAT_FILES_URL = "https://gigachat.devices.sberbank.ru/api/v1/files"
    private val GIGACHAT_API_URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"

    private var accessToken: String? = null

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val sslSocketFactory = sslContext.socketFactory

            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(60, TimeUnit.SECONDS)
            	.readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
            	.callTimeout(120, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания unsafe OkHttpClient", e)
            return OkHttpClient()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        clothingDetector = ClothingDetector(this)
        binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER

        setupButtons()

        permissionsRequestLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val isCameraGranted = permissions[Manifest.permission.CAMERA] ?: false

            if (isCameraGranted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Пожалуйста, предоставьте разрешение на камеру",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        checkAndRequestPermissions()
        cameraExecutor = Executors.newSingleThreadExecutor()
        getAccessToken()

        Log.d(TAG, "Activity создана")
    }

    private fun setupButtons() {
        binding.btnDescription.setOnClickListener {
            if (!isRequestInProgress) {
                takePhotoAndAnalyze("description")
            }
        }

        binding.btnCompatibility.setOnClickListener {
            if (!isRequestInProgress) {
                takePhotoAndAnalyze("compatibility")
            }
        }
    }

    private fun takePhotoAndAnalyze(requestType: String) {
        if (imageCapture == null) {
            showToast("Камера ещё не готова")
            return
        }

        setButtonsEnabled(false)
        updateStatus("Создание фото...", true)

        val photoFile = File(
            cacheDir,
            "photo_" + System.currentTimeMillis() + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Фото сохранено: " + photoFile.absolutePath)
                    uploadPhotoAndAnalyze(photoFile, requestType)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Ошибка создания фото", exc)
                    showToast("Ошибка создания фото")
                    setButtonsEnabled(true)
                    updateStatus("Готов к запросу", false)
                }
            }
        )
    }

    private fun optimizeAndUploadPhoto(originalFile: File, requestType: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeFile(originalFile.absolutePath)

                val maxSize = 800
                val width = bitmap.width
                val height = bitmap.height

                val scale = if (width > height) {
                    if (width > maxSize) maxSize.toFloat() / width else 1f
                } else {
                    if (height > maxSize) maxSize.toFloat() / height else 1f
                }

                val scaledWidth = (width * scale).toInt()
                val scaledHeight = (height * scale).toInt()

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

                val optimizedFile = File(cacheDir, "optimized_" + System.currentTimeMillis() + ".jpg")
                val outputStream = FileOutputStream(optimizedFile)
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                outputStream.flush()
                outputStream.close()

                val fileSize = optimizedFile.length()
                Log.d(TAG, "Оптимизированный файл: размер=" + fileSize + " байт, " +
                        "разрешение=" + scaledWidth + "x" + scaledHeight)

                originalFile.delete()

                uploadPhotoAndAnalyze(optimizedFile, requestType)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка оптимизации фото", e)
                originalFile.delete()
                withContext(Dispatchers.Main) {
                    showToast("Ошибка обработки фото")
                    setButtonsEnabled(true)
                    updateStatus("Готов к запросу", false)
                }
            }
        }
    }

    private fun uploadPhotoAndAnalyze(photoFile: File, requestType: String) {
        if (accessToken == null) {
            showToast("Токен не получен")
            speakText("Проверьте подключение к интернету")
            getAccessToken()
            photoFile.delete()
            setButtonsEnabled(true)
            updateStatus("Готов к запросу", false)
            return
        }

        updateStatus("Загрузка фото в GigaChat...", true)

        scope.launch(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        photoFile.name,
                        RequestBody.create("image/jpeg".toMediaType(), photoFile)
                    )
                    .addFormDataPart("purpose", "general")
                    .build()

                val uploadRequest = Request.Builder()
                    .url(GIGACHAT_FILES_URL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build()

                Log.d(TAG, "Загружаем файл в GigaChat...")

                val uploadResponse = client.newCall(uploadRequest).execute()
                val uploadResponseBody = uploadResponse.body?.string()

                Log.d(TAG, "Ответ загрузки: код=" + uploadResponse.code)

                if (uploadResponse.isSuccessful && uploadResponseBody != null) {
                    val uploadJson = JSONObject(uploadResponseBody)
                    val fileId = uploadJson.getString("id")

                    Log.d(TAG, "Файл загружен, ID: " + fileId)


                    photoFile.delete()

                    withContext(Dispatchers.Main) {
                        makeGigaChatRequestWithFile(fileId, requestType)
                    }
                } else {
                    Log.e(TAG, "Ошибка загрузки файла: " + uploadResponseBody)
                    photoFile.delete()
                    withContext(Dispatchers.Main) {
                        showToast("Ошибка загрузки: " + uploadResponse.code)
                        setButtonsEnabled(true)
                        updateStatus("Готов к запросу", false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Исключение при загрузке файла", e)
                photoFile.delete()
                withContext(Dispatchers.Main) {
                    showToast("Ошибка: " + e.message)
                    setButtonsEnabled(true)
                    updateStatus("Готов к запросу", false)
                }
            }
        }
    }

    private fun makeGigaChatRequestWithFile(fileId: String, requestType: String) {
        updateStatus("Отправка запроса...", true)

        scope.launch(Dispatchers.IO) {
            try {
                val prompt = when (requestType) {
                    "description" -> "Действуй строго по инструкции. Не добавляй лишних слов, комментариев, рассуждений и пояснений.  \n" +
                            "\n" +
                            "1. Если на картинке НЕТ одежды, верни false (без кавычек) и больше НИЧЕГО.  \n" +
                            "2. Если на картинке ЕСТЬ одежда:  \n" +
                            "  а) Начни ответ с названия типа основной одежды: футболка, рубашка, кофта, куртка, пальто, брюки, джинсы, шорты, юбка, платье, спортивные штаны и т.п.  \n" +
                            "  б) В одном–двух коротких предложениях опиши:  \n" +
                            "  - цвет;  \n" +
                            "  - принт (если есть – что изображено);  \n" +
                            "  - надпись (если есть – укажи текст надписи);  \n" +
                            "\n" +
                            "Не упоминай фон, части тела, лицо или другие объекты, только одежду.  \n" +
                            "\n" +
                            "Пример формата ответа (если одежда ЕСТЬ):  \n" +
                            "Футболка синего цвета с жёлтым принтом подсолнуха и надписью \"be happy\""

                    "compatibility" -> "Действуй строго по инструкции. Не добавляй лишних слов, комментариев, рассуждений и пояснений.\n" +
                            "\n" +
                            "СНАЧАЛА В УМЕ ОПРЕДЕЛИ:\n" +
                            "1) Есть ли на человеке одежда.\n" +
                            "2) Сколько РАЗНЫХ элементов одежды видно: 0, 1 или 2 и более.\n" +
                            "\n" +
                            "Под \"разными элементами одежды\" считаются отдельно:\n" +
                            "– верх (футболка, рубашка, кофта, свитер, куртка, пиджак, пальто и т.п.);\n" +
                            "– низ (брюки, джинсы, шорты, юбка и т.п.);\n" +
                            "– платье или комбинезон (считается как ОДИН элемент);\n" +
                            "– обувь (кроссовки, ботинки, туфли и т.п.);\n" +
                            "– головной убор (шапка, кепка, панама и т.п.);\n" +
                            "– видимая верхняя одежда поверх другой (куртка поверх футболки и т.п.).\n" +
                            "\n" +
                            "Если видна ХОТЯ БЫ ЧАСТЬ второго элемента (например, край футболки из-под куртки, пояс брюк, кусок юбки, видимая обувь), считай, что элементов 2 и более.\n" +
                            "\n" +
                            "ДАЛЬШЕ ДЕЙСТВУЙ ТАК:\n" +
                            "\n" +
                            "1. Если на картинке НЕТ одежды, верни ровно: false\n" +
                            "   Ничего больше не пиши.\n" +
                            "\n" +
                            "2. Если на картинке ЕСТЬ одежда и ты ВИДИШЬ РОВНО ОДИН элемент одежды (и НЕ видишь ни верха+низа, ни обуви, ни второй вещи поверх):\n" +
                            "   – верни ровно: one\n" +
                            "   – ничего больше не пиши.\n" +
                            "   – Если сомневаешься, один элемент там или их больше, СЧИТАЙ, ЧТО ЭЛЕМЕНТОВ НЕСКОЛЬКО (не пиши one).\n" +
                            "\n" +
                            "3. Если на картинке ЕСТЬ НЕСКОЛЬКО элементов одежды (2 и более):\n" +
                            "   – НЕ возвращай \"one\".\n" +
                            "   – В 1–3 коротких предложениях оцени, насколько вещи сочетаются по цвету и стилю:\n" +
                            "     • напиши, хорошо ли они сочетаются, частично сочетаются или плохо сочетаются;\n" +
                            "     • упомяни основные цвета и общий стиль (повседневный, спортивный, деловой, нарядный и т.п.);\n" +
                            "     • будь максимально доброжелательным, можно сделать мягкий комплимент («смотрится стильно», «образ яркий и живой», «выглядит уютно»).\n" +
                            "\n" +
                            "Не описывай фон, части тела, лицо или другие объекты — только одежду и её сочетаемость.\n" +
                            "\n" +
                            "Примеры формата ответа (когда одежды несколько):\n" +
                            "– «Цвета хорошо сочетаются: тёмный верх и светлый низ создают спокойный повседневный образ. Смотрится аккуратно и стильно.»\n" +
                            "– «Вещи сочетаются частично: контрастные цвета делают образ ярким и заметным. Получается смелый и живой стиль.»\n" +
                            "– «Цвета между собой сочетаются слабо, образ выглядит немного пёстрым. Но такой наряд подойдёт тем, кто любит очень яркий и нестандартный стиль.»"
                    else -> "Опиши что на изображении"
                }

                val jsonBody = JSONObject().apply {
                    put("model", "GigaChat-2-Pro")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                            put("attachments", JSONArray().apply {
                                put(fileId)
                            })
                        })
                    })
                    put("temperature", 0.7)
                    put("max_tokens", 200)
                }

                Log.d(TAG, "JSON запрос: " + jsonBody.toString())

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(GIGACHAT_API_URL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                Log.d(TAG, "Ответ GigaChat: код=" + response.code)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            val choices = json.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val rmessage = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")

                                Log.d(TAG, "Получен ответ: " + rmessage)
                                val message = rmessage.trim()
                                if (message == "one") {
                                    speakText("Нельзя определить сочетаемость, на фото только один элемент одежды")
                                } else if (message == "false") {
                                    speakText("На фото нет одежды")
                                } else {
                                    speakText(message)
                                }

                                updateStatus("Получен ответ", false)
                            } else {
                                showToast("Пустой ответ от GigaChat")
                                updateStatus("Ошибка: пустой ответ", false)
                                setButtonsEnabled(true)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка парсинга ответа", e)
                            showToast("Ошибка обработки ответа")
                            updateStatus("Ошибка обработки", false)
                            setButtonsEnabled(true)
                        }
                    } else {
                        Log.e(TAG, "Ошибка запроса: " + response.code + " - " + responseBody)
                        showToast("Ошибка запроса: " + response.code)
                        updateStatus("Ошибка запроса", false)
                        setButtonsEnabled(true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Исключение при запросе", e)
                    showToast("Ошибка: " + e.message)
                    updateStatus("Ошибка соединения", false)
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun getAccessToken() {
        scope.launch(Dispatchers.IO) {
            try {
                val credentials = GIGACHAT_CLIENT_ID + ":" + GIGACHAT_CLIENT_SECRET
                val basicAuth = "Basic " + android.util.Base64.encodeToString(
                    credentials.toByteArray(),
                    android.util.Base64.NO_WRAP
                )

                val requestBody = FormBody.Builder()
                    .add("scope", "GIGACHAT_API_PERS")
                    .build()

                val request = Request.Builder()
                    .url(GIGACHAT_AUTH_URL)
                    .post(requestBody)
                    .addHeader("Authorization", basicAuth)
                    .addHeader("RqUID", UUID.randomUUID().toString())
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                Log.d(TAG, "Отправка запроса на получение токена...")

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    accessToken = json.getString("access_token")
                    Log.d(TAG, "Access token получен успешно")
                    withContext(Dispatchers.Main) {
                        showToast("Токен получен!")
                    }
                } else {
                    Log.e(TAG, "Ошибка получения токена: " + responseBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Исключение при получении токена", e)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnDescription.isEnabled = enabled
        binding.btnCompatibility.isEnabled = enabled
        binding.btnDescription.alpha = if (enabled) 1.0f else 0.5f
        binding.btnCompatibility.alpha = if (enabled) 1.0f else 0.5f
        isRequestInProgress = !enabled
    }

    private fun updateStatus(text: String, showProgress: Boolean) {
        binding.statusText.text = text
        binding.loadingProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && 
                        result != TextToSpeech.LANG_NOT_SUPPORTED

            if (isTtsReady) {
                Log.d(TAG, "TTS инициализирован успешно")
            }
        }
    }

    private fun speakText(text: String) {
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        setButtonsEnabled(true)
                        updateStatus("Готов к запросу", false)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        setButtonsEnabled(true)
                        updateStatus("Ошибка озвучивания", false)
                    }
                }
            })
        } else {
            showToast(text)
            setButtonsEnabled(true)
            updateStatus("Готов к запросу", false)
        }
    }

    private fun checkAndRequestPermissions() {
        val isCameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        when {
            isCameraGranted -> startCamera()
            else -> permissionsRequestLauncher.launch(arrayOf(Manifest.permission.CAMERA))
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

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

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

                bindCamera(cameraSelector, preview, imageAnalysis, imageCapture!!)

            } catch (exc: Exception) {
                Log.e(TAG, "Ошибка инициализации камеры", exc)
                Toast.makeText(this, "Ошибка запуска камеры", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(
        cameraSelector: CameraSelector,
        preview: Preview,
        imageAnalysis: ImageAnalysis,
        imageCapture: ImageCapture
    ) {
        try {
            cameraProvider?.unbindAll()

            cameraProvider?.bindToLifecycle(
                this as LifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
                imageCapture
            )

            Log.d(TAG, "Камера успешно запущена")
        } catch (exc: Exception) {
            Log.e(TAG, "Ошибка привязки камеры", exc)
        }
    }

    private fun handleDetections(detections: List<DetectionResult>) {
        if (detections.isNotEmpty()) {
            val mainDetection = detections.maxByOrNull { it.confidence }
            mainDetection?.let { detection ->
                val className = clothingDetector.getClassName(detection.classId)
                lastDetectedClothing = className
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        tts?.stop()
        tts?.shutdown()

        imageAnalysis?.clearAnalyzer()
        cameraExecutor.shutdown()
        clothingDetector.clean()

        scope.cancel()

        _binding = null
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}