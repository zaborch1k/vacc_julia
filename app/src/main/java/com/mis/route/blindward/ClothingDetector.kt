package com.mis.route.blindward

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ClothingDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var inputTensor: Tensor? = null
    private var inputSize = 0
    private var inputShape: IntArray? = null

    private val labels = mutableListOf<String>()

    companion object {
        private const val MODEL_FILE = "best_float32.tflite"
        private const val LABELS_FILE = "dataset-labels.txt"
        private const val TAG = "ClothingDetector"
    }

    init {
        Log.d(TAG, "Детектор запущен")
        loadModel()
        loadLabels()
    }

    private fun loadModel() {
        try {
            val options = Interpreter.Options()
            options.setUseNNAPI(false)

            val model = loadModelFile()
            interpreter = Interpreter(model, options)

            // Получение информации из Тензора
            inputTensor = interpreter?.getInputTensor(0)
            inputShape = inputTensor?.shape()
            inputSize = inputShape?.get(1) ?: 640

            Log.d(TAG, "Форма входного тензора: ${inputShape?.contentToString()}")
            Log.d(TAG, "Размер входного изображения: $inputSize")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки модели: ${e.message}", e)
        }
    }

    private fun loadModelFile(): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadLabels() {
        try {
            context.assets.open(LABELS_FILE).bufferedReader().useLines { lines ->
                labels.addAll(lines)
            }
            Log.d(TAG, "Загружено ${labels.size} меток: $labels")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки меток: ${e.message}", e)
        }
    }

    fun getClassName(classId: Int): String {
        return if (classId in labels.indices) {
            labels[classId]
        } else {
            "unknown_$classId"
        }
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (interpreter == null) {
            Log.e(TAG, "Интерпретатор не инициализирован")
            return emptyList()
        }

        return try {
            val input = preprocessImage(bitmap)

            // Получение информации о тензоре
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            Log.d(TAG, "Форма выходного тензора: ${outputShape.contentToString()}")

            // Создание входного буфера
            val output = Array(1) { Array(12) { FloatArray(11109) } }

            val startTime = System.currentTimeMillis()
            interpreter!!.run(input, output)
            val endTime = System.currentTimeMillis()

            Log.d(TAG, "Время inference: ${endTime - startTime} ms")
            postprocessResults(output[0], bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка детекции: ${e.message}", e)
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inputBuffer = ByteBuffer.allocateDirect(inputTensor!!.numBytes())
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        var pixel = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val value = intValues[pixel++]

                when (inputTensor!!.dataType()) {
                    org.tensorflow.lite.DataType.FLOAT32 -> {
                        inputBuffer.putFloat(Color.blue(value) / 255.0f)   // B
                        inputBuffer.putFloat(Color.green(value) / 255.0f)  // G
                        inputBuffer.putFloat(Color.red(value) / 255.0f)    // R
                    }
                    org.tensorflow.lite.DataType.UINT8 -> {
                        inputBuffer.put((Color.blue(value) and 0xFF).toByte())
                        inputBuffer.put((Color.green(value) and 0xFF).toByte())
                        inputBuffer.put((Color.red(value) and 0xFF).toByte())
                    }
                    else -> throw RuntimeException("Неизвестный тип данных")
                }
            }
        }

        return inputBuffer
    }

    private fun postprocessResults(
        output: Array<FloatArray>, // [12, 11109]
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val confidenceThreshold = 0.15f

        val numBoxes = output[0].size
        val numClasses = labels.size

        Log.d(TAG, "Начало постобработки: $numBoxes боксов, $numClasses классов")

        var highConfidenceCount = 0
        var totalBoxesChecked = 0

        for (boxIndex in 0 until numBoxes) {
            totalBoxesChecked++

            val objectness = output[4][boxIndex]

            if (objectness > 0.05f) {
                highConfidenceCount++

                var maxClassProb = 0f
                var classId = 0

                for (classIndex in 0 until numClasses) {

                    if (5 + classIndex < output.size) {
                        val classProb = output[5 + classIndex][boxIndex]
                        if (classProb > maxClassProb) {
                            maxClassProb = classProb
                            classId = classIndex
                        }
                    } else {
                        Log.w(TAG, "Выход за границы массива: classIndex=$classIndex, output.size=${output.size}")
                        break
                    }
                }

                val finalConfidence = objectness * maxClassProb

                if (finalConfidence > confidenceThreshold) {
                    val xCenter = output[0][boxIndex]
                    val yCenter = output[1][boxIndex]
                    val width = output[2][boxIndex]
                    val height = output[3][boxIndex]

                    // Конвертация в абсолютные координаты с проверкой границ
                    val absX1 = maxOf(0f, (xCenter - width / 2) * originalWidth)
                    val absY1 = maxOf(0f, (yCenter - height / 2) * originalHeight)
                    val absX2 = minOf(originalWidth.toFloat(), (xCenter + width / 2) * originalWidth)
                    val absY2 = minOf(originalHeight.toFloat(), (yCenter + height / 2) * originalHeight)

                    // Проверяем что bounding box корректный
                    if (absX2 > absX1 && absY2 > absY1 && width > 0 && height > 0) {
                        results.add(
                            DetectionResult(
                                x1 = absX1,
                                y1 = absY1,
                                x2 = absX2,
                                y2 = absY2,
                                confidence = finalConfidence,
                                classId = classId
                            )
                        )

                        Log.d(TAG, "Найдена детекция: ${getClassName(classId)} " +
                                "с уверенностью ${String.format("%.2f", finalConfidence)} " +
                                "в координатах [${absX1.toInt()},${absY1.toInt()},${absX2.toInt()},${absY2.toInt()}]")
                    }
                }
            }
        }

        Log.d(TAG, "Проверено боксов: $totalBoxesChecked, с objectness > 0.05: $highConfidenceCount")
        Log.d(TAG, "Корректных детекций до NMS: ${results.size}")

        return applyNMS(results)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val selected = mutableListOf<DetectionResult>()
        val sortedDetections = detections.sortedByDescending { it.confidence }

        sortedDetections.forEach { detection ->
            var shouldAdd = true

            for (selectedDetection in selected) {
                if (calculateIOU(detection, selectedDetection) > 0.5f) {
                    shouldAdd = false
                    break
                }
            }

            if (shouldAdd) {
                selected.add(detection)
            }
        }

        Log.d(TAG, "После NMS: ${selected.size}")
        return selected
    }

    private fun calculateIOU(box1: DetectionResult, box2: DetectionResult): Float {
        val intersectionLeft = maxOf(box1.x1, box2.x1)
        val intersectionTop = maxOf(box1.y1, box2.y1)
        val intersectionRight = minOf(box1.x2, box2.x2)
        val intersectionBottom = minOf(box1.y2, box2.y2)

        val intersectionArea = maxOf(0f, intersectionRight - intersectionLeft) *
                maxOf(0f, intersectionBottom - intersectionTop)

        val area1 = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
        val area2 = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)

        return intersectionArea / (area1 + area2 - intersectionArea)
    }

    fun clean() {
        interpreter?.close()
    }
}