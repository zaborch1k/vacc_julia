package com.ihateandroid.blindward

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ihateandroid.blindward.databinding.ActivityMainBinding
import android.Manifest
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding get() = _binding!!
    private lateinit var cameraPermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionRationaleDialog: AlertDialog
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraPermissionRationaleDialog = createDialog(
            "Дай разрешения на камеру , пожажа",
            "Слушай , мы не можем без разрешения камеру включить",
            "Запросить ещё раз",
            { cameraPermissionRequestLauncher.launch(Manifest.permission.CAMERA) },
            "Мне пуфик"
        )

        cameraPermissionRequestLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {
                    startCamera()
                } else {
                    if (cameraPermissionRationaleDialog.isShowing)
                        cameraPermissionRequestLauncher.launch(Manifest.permission.CAMERA)
                    else cameraPermissionRationaleDialog.show()
                }
            }


        binding.startCameraBtn.setOnClickListener { requestCameraPermissionFlow() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }



    // STEP 2- define the permission request flow
    private fun requestCameraPermissionFlow() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // You can use the API that requires the permission.
                startCamera()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.CAMERA
            ) -> {
                if (cameraPermissionRationaleDialog.isShowing)
                    cameraPermissionRequestLauncher.launch(Manifest.permission.CAMERA)
                else cameraPermissionRationaleDialog.show()
            }

            else -> {
                cameraPermissionRequestLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
        }
    }

    private fun createDialog(
        title: String,
        message: String,
        posBtnText: String? = null,
        posBtnAction: (() -> Unit)? = null,
        negBtnText: String? = null,
        negBtnAction: (() -> Unit)? = null,
        isCancelable: Boolean = false
    ): AlertDialog {
        return MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(posBtnText) { dialog, _ ->
                dialog.dismiss()
                posBtnAction?.invoke()
            }
            .setNegativeButton(negBtnText) { dialog, _ ->
                dialog.dismiss()
                negBtnAction?.invoke()
            }
            .setCancelable(isCancelable)
            .create()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Показ камеры
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

            } catch (exc: Exception) {
                Log.e("TAG", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
        cameraExecutor.shutdown()
    }
}