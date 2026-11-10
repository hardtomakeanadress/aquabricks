package com.aquabricks.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: View
    private lateinit var manageButton: TextView
    private lateinit var permissionPanel: View
    private lateinit var grantButton: Button
    private lateinit var flashView: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var permissionDenied = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionDenied = false
                permissionPanel.visibility = View.GONE
                startCamera()
            } else {
                permissionDenied = true
                updatePermissionPanel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        manageButton = findViewById(R.id.manageButton)
        permissionPanel = findViewById(R.id.permissionPanel)
        grantButton = findViewById(R.id.grantButton)
        flashView = findViewById(R.id.flash)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        SystemBars.marginBelowStatusBar(findViewById(R.id.topPanel), 16f)
        SystemBars.marginBelowStatusBar(manageButton, 16f)
        SystemBars.marginAboveNavigationBar(findViewById(R.id.bottomPanel), 26f)
        captureButton.setOnClickListener { capture() }
        manageButton.setOnClickListener { TankManagerLauncher.choose(this) }
        grantButton.setOnClickListener {
            if (permissionDenied) {
                openAppSettings()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        cleanupOldScans()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            startCamera()
        } else {
            updatePermissionPanel()
            permissionPanel.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionPanel() {
        grantButton.text = if (permissionDenied) getString(R.string.open_settings) else getString(R.string.grant_camera)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun startCamera() {
        if (camera != null || !hasCameraPermission()) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                permissionPanel.visibility = View.GONE
            } catch (error: Exception) {
                Toast.makeText(
                    this,
                    "Could not start the camera. Restart the app.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "Camera is still warming up...", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        captureButton.isEnabled = false
        captureButton.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    captureButton.isEnabled = true
                    captureButton.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    flash()
                    startActivity(
                        Intent(this@MainActivity, EditActivity::class.java)
                            .putExtra(EditActivity.EXTRA_PHOTO_PATH, file.absolutePath)
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    captureButton.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    Toast.makeText(
                        this@MainActivity,
                        "Could not take the photo. Try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun flash() {
        flashView.visibility = View.VISIBLE
        flashView.alpha = 0.75f
        flashView.animate().alpha(0f).setDuration(280).withEndAction {
            flashView.visibility = View.GONE
        }.start()
    }

    private fun cleanupOldScans() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("scan_") && file.lastModified() < cutoff) file.delete()
            }
        } catch (_: Exception) {
        }
    }
}
