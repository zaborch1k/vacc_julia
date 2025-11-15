package com.mis.route.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mis.route.blindward.ClothingDetector
import com.mis.route.blindward.DetectionResult
import java.io.ByteArrayOutputStream

class CameraAnalyzer(
    private val clothingDetector: ClothingDetector,
    private val onDetectionResult: (List<DetectionResult>) -> Unit
) : ImageAnalysis.Analyzer {

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val image = imageProxy.image ?: return

            Log.d("CameraAnalyzer", "Получен кадр: ${image.width}x${image.height}, формат: ${image.format}")

            val bitmap = when (image.format) {
                ImageFormat.YUV_420_888 -> convertYUV420ToBitmap(image)
                ImageFormat.NV21 -> convertNV21ToBitmap(image) // 👈 Формат 1 = NV21
                else -> {
                    Log.e("CameraAnalyzer", "Неизвестный формат: ${image.format}")
                    return
                }
            }

            if (bitmap != null) {
                // Поворачиваем bitmap для портретного режима
                val rotatedBitmap = rotateBitmap(bitmap, 90f)
                val detections = clothingDetector.detect(rotatedBitmap)
                Log.d("CameraAnalyzer", "Найдено объектов: ${detections.size}")
                onDetectionResult(detections)
            } else {
                Log.e("CameraAnalyzer", "Не удалось конвертировать изображение в Bitmap")
                onDetectionResult(emptyList())
            }

        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Ошибка анализа: ${e.message}", e)
            onDetectionResult(emptyList())
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    @ExperimentalGetImage
    private fun convertYUV420ToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Y plane
            yBuffer.get(nv21, 0, ySize)

            // U and V planes
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
            val imageBytes = out.toByteArray()
            out.close()

            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Ошибка конвертации YUV420: ${e.message}", e)
            return null
        }
    }

    @ExperimentalGetImage
    private fun convertNV21ToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uvBuffer = planes[1].buffer

            val ySize = yBuffer.remaining()
            val uvSize = uvBuffer.remaining()

            val nv21 = ByteArray(ySize + uvSize)

            // Y plane
            yBuffer.get(nv21, 0, ySize)
            // UV plane
            uvBuffer.get(nv21, ySize, uvSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
            val imageBytes = out.toByteArray()
            out.close()

            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Ошибка конвертации NV21: ${e.message}", e)
            return null
        }
    }
}
