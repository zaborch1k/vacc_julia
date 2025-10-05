package com.mis.route.myapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
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
                ImageFormat.NV21 -> convertNV21ToBitmap(image)
                else -> {
                    return
                }
            }

            if (bitmap != null) {
                val detections = clothingDetector.detect(bitmap)
                Log.d("CameraAnalyzer", "Найдено объектов: ${detections.size}")
                onDetectionResult(detections)
            } else {
                onDetectionResult(emptyList())
            }

        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Ошибка анализа: ${e.message}")
            onDetectionResult(emptyList())
        } finally {
            imageProxy.close()
        }
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

            // Y плоскость
            yBuffer.get(nv21, 0, ySize)

            // КU и V плоскости (!важен порядок!)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()

            // YUV в JPEG, потом в Bitmap
            yuvImage.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                80, // качество
                out
            )

            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e("CameraAnalyzer", " Ошибка конвертации: ${e.message}")
            return null
        }
    }

    @ExperimentalGetImage
    private fun convertNV21ToBitmap(image: Image): Bitmap? {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Ошибка конвертации NV21: ${e.message}")
            return null
        }
    }
}
