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
import java.util.PriorityQueue

class ClothingDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var inputTensor: Tensor? = null
    private var inputSize = 640
    private var inputShape: IntArray? = intArrayOf(1, 640, 640, 3)

    // YOLO параметры
    private val numClasses = 7
    private val confidenceThreshold = 0.3f
    private val iouThreshold = 0.45f

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
            options.setNumThreads(4)

            val model = loadModelFile()
            interpreter = Interpreter(model, options)


            inputTensor = interpreter?.getInputTensor(0)
            inputShape = inputTensor?.shape()


            inputSize = inputShape?.get(1) ?: 640
            val inputChannels = inputShape?.get(3) ?: 3

            Log.d(TAG, "Форма входного тензора: ${inputShape?.contentToString()}")
            Log.d(TAG, "Размер входного изображения: $inputSize")
            Log.d(TAG, "Количество каналов: $inputChannels")
            Log.d(TAG, "Тип данных: ${inputTensor!!.dataType()}")
            Log.d(TAG, "Ожидаемый размер буфера: ${inputTensor!!.numBytes()} байт")

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
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape()

            Log.d(TAG, "Форма выходного тензора: ${outputShape.contentToString()}")
            Log.d(TAG, "Размер выходного буфера: ${outputTensor.numBytes()} байт")

            val startTime = System.currentTimeMillis()

            // выходной буфер
            when (outputShape.size) {
                3 -> { // Формат [1, X, Y] - YOLOv8
                    val output = Array(1) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
                    interpreter!!.run(input, output)
                    postprocessResultsYoloV8(output[0], bitmap.width, bitmap.height)
                }
                4 -> { // Формат [1, X, Y, Z] - YOLOv5
                    val output = Array(1) { Array(outputShape[1]) { Array(outputShape[2]) { FloatArray(outputShape[3]) } } }
                    interpreter!!.run(input, output)
                    postprocessResultsYoloV5(output[0], bitmap.width, bitmap.height)
                }
                2 -> { // Формат [1, X] - плоский вывод
                    val output = Array(1) { FloatArray(outputShape[1]) }
                    interpreter!!.run(input, output)
                    postprocessResultsFlat(output[0], bitmap.width, bitmap.height)
                }
                else -> {
                    Log.e(TAG, "Неизвестный формат вывода: ${outputShape.contentToString()}")
                    emptyList()
                }
            }.also {
                val endTime = System.currentTimeMillis()
                Log.d(TAG, "Время inference: ${endTime - startTime} ms")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка детекции: ${e.message}", e)
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // Масштабирование
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Создаем буфер корректного размера
        val inputBuffer = ByteBuffer.allocateDirect(inputTensor!!.numBytes())
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        // Определяем тип данных модели
        when (inputTensor!!.dataType()) {
            org.tensorflow.lite.DataType.FLOAT32 -> {
                processAsFloat32(inputBuffer, intValues)
            }
            org.tensorflow.lite.DataType.UINT8 -> {
                processAsUInt8(inputBuffer, intValues)
            }
            else -> {
                throw RuntimeException("Неподдерживаемый тип данных: ${inputTensor!!.dataType()}")
            }
        }

        // удаление из памяти
        resizedBitmap.recycle()

        return inputBuffer
    }
    //Тестовая заглушка
    private fun postprocessResultsFlat(
        output: FloatArray,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {

        Log.d(TAG, "Плоский вывод: ${output.size} элементов")

        // Временная заглушка
        return emptyList()
    }

    private fun processAsFloat32(buffer: ByteBuffer, pixels: IntArray) {
        var pixel = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val value = pixels[pixel++]



                // RGB
                buffer.putFloat(Color.red(value) / 255.0f)
                buffer.putFloat(Color.green(value) / 255.0f)
                buffer.putFloat(Color.blue(value) / 255.0f)

            }
        }
    }

    private fun processAsUInt8(buffer: ByteBuffer, pixels: IntArray) {
        var pixel = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val value = pixels[pixel++]

                buffer.put((Color.red(value) and 0xFF).toByte())
                buffer.put((Color.green(value) and 0xFF).toByte())
                buffer.put((Color.blue(value) and 0xFF).toByte())
            }
        }
    }

    // Для YOLOv8
    private fun postprocessResultsYoloV8(
        output: Array<FloatArray>, // [84, 8400]
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val numBoxes = output[0].size // 8400

        Log.d(TAG, "Постобработка YOLOv8: ${output.size} x $numBoxes")

        for (boxIndex in 0 until numBoxes) {
            val xCenter = output[0][boxIndex]
            val yCenter = output[1][boxIndex]
            val width = output[2][boxIndex]
            val height = output[3][boxIndex]

            // Находим максимальную уверенность класса
            var maxConfidence = 0f
            var classId = -1

            for (classIndex in 0 until numClasses) {
                val confidence = output[4 + classIndex][boxIndex]
                if (confidence > maxConfidence) {
                    maxConfidence = confidence
                    classId = classIndex
                }
            }

            if (maxConfidence > confidenceThreshold && classId != -1) {
                // конвертация в координаты
                val absX1 = (xCenter - width / 2) * originalWidth
                val absY1 = (yCenter - height / 2) * originalHeight
                val absX2 = (xCenter + width / 2) * originalWidth
                val absY2 = (yCenter + height / 2) * originalHeight

                if (absX2 > absX1 && absY2 > absY1 && width > 0 && height > 0) {
                    results.add(
                        DetectionResult(
                            x1 = maxOf(0f, absX1),
                            y1 = maxOf(0f, absY1),
                            x2 = minOf(originalWidth.toFloat(), absX2),
                            y2 = minOf(originalHeight.toFloat(), absY2),
                            confidence = maxConfidence,
                            classId = classId
                        )
                    )

                    Log.d(TAG, "Обнаружено: ${getClassName(classId)} " +
                            "с уверенностью ${String.format("%.2f", maxConfidence)}")
                }
            }
        }

        Log.d(TAG, "Найдено детекций до NMS: ${results.size}")
        return applyNMS(results)
    }
    private fun postprocessResultsYolo(
        output: Array<FloatArray>,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val numBoxes = output[0].size

        for (boxIndex in 0 until numBoxes) {
            val objectness = output[4][boxIndex]

            if (objectness > 0.1f) {
                var maxClassProb = 0f
                var classId = -1

                for (classIndex in 0 until numClasses) {
                    if (5 + classIndex < output.size) {
                        val classProb = output[5 + classIndex][boxIndex]
                        if (classProb > maxClassProb) {
                            maxClassProb = classProb
                            classId = classIndex
                        }
                    }
                }

                val confidence = objectness * maxClassProb

                if (confidence > confidenceThreshold && classId != -1) {
                    val xCenter = output[0][boxIndex]
                    val yCenter = output[1][boxIndex]
                    val width = output[2][boxIndex]
                    val height = output[3][boxIndex]

                    val absX1 = maxOf(0f, (xCenter - width / 2) * originalWidth)
                    val absY1 = maxOf(0f, (yCenter - height / 2) * originalHeight)
                    val absX2 = minOf(originalWidth.toFloat(), (xCenter + width / 2) * originalWidth)
                    val absY2 = minOf(originalHeight.toFloat(), (yCenter + height / 2) * originalHeight)

                    if (absX2 > absX1 && absY2 > absY1) {
                        results.add(
                            DetectionResult(
                                x1 = absX1,
                                y1 = absY1,
                                x2 = absX2,
                                y2 = absY2,
                                confidence = confidence,
                                classId = classId
                            )
                        )
                    }
                }
            }
        }

        return applyNMS(results)
    }
    private fun postprocessResultsYoloV5(
        output: Array<Array<FloatArray>>, // [84, 80, 80]
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val gridSize = output[0].size // 80
        val numAnchors = output[0][0].size // 80
        val featuresPerAnchor = output.size // 84

        Log.d(TAG, "Постобработка YOLOv5: grid=$gridSize, anchors=$numAnchors, features=$featuresPerAnchor")

        for (i in 0 until gridSize) {
            for (j in 0 until numAnchors) {
                // Извлекаем bounding box [x, y, w, h, obj_score, class_scores...]
                val x = output[0][i][j]
                val y = output[1][i][j]
                val w = output[2][i][j]
                val h = output[3][i][j]
                val objectness = output[4][i][j]

                if (objectness > 0.5f) {
                    var maxClassProb = 0f
                    var classId = -1

                    // Находим класс с максимальной уверенностью
                    for (classIndex in 0 until numClasses) {
                        if (5 + classIndex < featuresPerAnchor) {
                            val classProb = output[5 + classIndex][i][j]
                            if (classProb > maxClassProb) {
                                maxClassProb = classProb
                                classId = classIndex
                            }
                        }
                    }

                    val confidence = objectness * maxClassProb

                    if (confidence > confidenceThreshold && classId != -1) {
                        // Конвертируем в абсолютные координаты
                        val scaleX = originalWidth.toFloat() / gridSize
                        val scaleY = originalHeight.toFloat() / gridSize

                        val absX1 = (x - w / 2) * scaleX
                        val absY1 = (y - h / 2) * scaleY
                        val absX2 = (x + w / 2) * scaleX
                        val absY2 = (y + h / 2) * scaleY

                        if (absX2 > absX1 && absY2 > absY1) {
                            results.add(
                                DetectionResult(
                                    x1 = maxOf(0f, absX1),
                                    y1 = maxOf(0f, absY1),
                                    x2 = minOf(originalWidth.toFloat(), absX2),
                                    y2 = minOf(originalHeight.toFloat(), absY2),
                                    confidence = confidence,
                                    classId = classId
                                )
                            )
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Найдено детекций до NMS: ${results.size}")
        return applyNMS(results)
    }


    private fun postprocessResultsYoloTransposed(
        output: Array<FloatArray>, // [8400, 84]
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val numBoxes = output.size // 8400

        Log.d(TAG, "Постобработка транспонированного YOLO: $numBoxes боксов")

        for (boxIndex in 0 until numBoxes) {
            val boxData = output[boxIndex]

            val xCenter = boxData[0]
            val yCenter = boxData[1]
            val width = boxData[2]
            val height = boxData[3]

            // Находим максимальную уверенность класса
            var maxConfidence = 0f
            var classId = -1

            for (classIndex in 0 until numClasses) {
                if (4 + classIndex < boxData.size) {
                    val confidence = boxData[4 + classIndex]
                    if (confidence > maxConfidence) {
                        maxConfidence = confidence
                        classId = classIndex
                    }
                }
            }

            if (maxConfidence > confidenceThreshold && classId != -1) {

                val absX1 = (xCenter - width / 2) * originalWidth
                val absY1 = (yCenter - height / 2) * originalHeight
                val absX2 = (xCenter + width / 2) * originalWidth
                val absY2 = (yCenter + height / 2) * originalHeight

                if (absX2 > absX1 && absY2 > absY1 && width > 0 && height > 0) {
                    results.add(
                        DetectionResult(
                            x1 = maxOf(0f, absX1),
                            y1 = maxOf(0f, absY1),
                            x2 = minOf(originalWidth.toFloat(), absX2),
                            y2 = minOf(originalHeight.toFloat(), absY2),
                            confidence = maxConfidence,
                            classId = classId
                        )
                    )
                }
            }
        }

        Log.d(TAG, "Найдено детекций до NMS: ${results.size}")
        return applyNMS(results)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        val selected = mutableListOf<DetectionResult>()
        val sortedDetections = detections.sortedByDescending { it.confidence }

        sortedDetections.forEach { detection ->
            var shouldAdd = true
            for (selectedDetection in selected) {
                if (calculateIOU(detection, selectedDetection) > iouThreshold) {
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

        return intersectionArea / (area1 + area2 - intersectionArea + 1e-7f)
    }

    fun clean() {
        interpreter?.close()
    }
}