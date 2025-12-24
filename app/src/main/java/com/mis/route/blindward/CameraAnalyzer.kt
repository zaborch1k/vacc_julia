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
            val image: Image? = imageProxy.image
            if (image == null) {
                Log.e("CameraAnalyzer", "Image is null")
                onDetectionResult(emptyList())
                return
            }

            Log.d("CameraAnalyzer", "Получен кадр: ${image.width}x${image.height}, формат: ${image.format}")

            val bitmap = convertYUVToBitmap(image)

            if (bitmap != null) {
                // bitmap  в портретный режим
                val rotatedBitmap = rotateBitmap(bitmap, 90f)

                val detections = clothingDetector.detect(rotatedBitmap)
                Log.d("CameraAnalyzer", "Найдено объектов: ${detections.size}")
                onDetectionResult(detections)

                // Очистка bitmap
                bitmap.recycle()
                rotatedBitmap.recycle()
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
    private fun convertYUVToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            if (planes.size < 3) {
                Log.e("CameraAnalyzer", "Недостаточно planes в YUV изображении: ${planes.size}")
                return null
            }

            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Y plane
            yBuffer.get(nv21, 0, ySize)

            // U & V planes
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()

            // качество сжатия
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val imageBytes = out.toByteArray()
            out.close()

            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Ошибка конвертации YUV: ${e.message}", e)
            return null
        }
    }
}