package com.mis.route.blindward

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer


class ClothingDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val inputSize = 736

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

            // Загрузка модели
            val model = loadModelFile()
            interpreter = Interpreter(model, options)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки модели")
        }
    }

    private fun loadModelFile(): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd("best_float32.tflite")
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
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки dataset-labels.txt", e)
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
            Log.e("ClothingDetector", "Ошибка интерпритатора")
            return emptyList()
        }

        return try {
            val input = preprocessImage(bitmap)

            val output = Array(1) { FloatArray(736 * 6) }

            val startTime = System.currentTimeMillis()
            interpreter!!.run(input, output)
            val endTime = System.currentTimeMillis()

            Log.d(TAG, "Время: ${endTime - startTime} ms")
            postprocessResults(output[0])

        } catch (e: Exception) {
            emptyList()
        }
    }

    //Преобразование изображений
    private fun preprocessImage(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }

        for (x in 0 until inputSize) {
            for (y in 0 until inputSize) {
                val pixel = resizedBitmap.getPixel(x, y)
                input[0][x][y][0] = Color.red(pixel) / 255.0f
                input[0][x][y][1] = Color.green(pixel) / 255.0f
                input[0][x][y][2] = Color.blue(pixel) / 255.0f
            }
        }
        return input
    }

    // Постпроцессинг результатов
    private fun postprocessResults(output: FloatArray): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val confidenceThreshold = 0.5f

        for (i in output.indices step 6) {
            if (i + 5 >= output.size) break

            val confidence = output[i + 4]
            if (confidence > confidenceThreshold) {
                val x1 = output[i]
                val y1 = output[i + 1]
                val x2 = output[i + 2]
                val y2 = output[i + 3]
                val classId = output[i + 5].toInt()

                results.add(DetectionResult(x1, y1, x2, y2, confidence, classId))
            }
        }

        Log.d(TAG, "Raw detections: ${results.size}")
        return applyNMS(results)
    }

    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        return detections.sortedByDescending { it.confidence }.take(10)
    }

    fun clean() {
        interpreter?.close()
    }
}