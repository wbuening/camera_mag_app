package com.example.magnifier

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.magnifier.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var camera: androidx.camera.core.Camera
    private var imageAnalysis: ImageAnalysis? = null
    private var isInverted = false
    private var isLight = false
    private var currentZoomRatio = 1.0f
    private var isFrozen = false
    private var shouldResetZoom = true

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // Letztes Frame – bereits gezoomt durch CameraX!
    private var lastRawBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentZoomRatio = 1.0f
        isFrozen = false
        shouldResetZoom = true

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.controlsLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16, 16, 16, 16 + systemBars.bottom)
            insets
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupControls()

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isFrozen) return false
                val newZoom = (currentZoomRatio * detector.scaleFactor).coerceIn(1.0f, 10.0f)
                currentZoomRatio = newZoom
                if (::camera.isInitialized) {
                    camera.cameraControl.setZoomRatio(currentZoomRatio)
                }
                runOnUiThread { updateZoomUI() }
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFreeze()
                return true
            }
        })

        setupPinchToZoom()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupPinchToZoom() {
        val onTouchListener = View.OnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
        binding.viewFinder.setOnTouchListener(onTouchListener)
        binding.processedImageView.setOnTouchListener(onTouchListener)
    }

    private fun setupControls() {
        binding.zoomSlider.max = 90
        updateZoomUI()

        binding.zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isFrozen) {
                    currentZoomRatio = 1.0f + (progress / 10.0f)
                    if (::camera.isInitialized) {
                        camera.cameraControl.setZoomRatio(currentZoomRatio)
                    }
                    binding.updateZoomText(currentZoomRatio)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.invertToggle.setOnCheckedChangeListener { _, isChecked ->
            isInverted = isChecked
            shouldResetZoom = false
            startCamera()
        }

        binding.lightToggle.setOnCheckedChangeListener { _, isChecked ->
            isLight = isChecked
            applyChangeLight()
        }
    }

    private fun ActivityMainBinding.updateZoomText(zoom: Float) {
        zoomText.text = String.format("%.1fx", zoom)
    }

    private fun updateZoomUI() {
        val progress = ((currentZoomRatio - 1.0f) * 10).toInt().coerceIn(0, 90)
        binding.zoomSlider.setProgress(progress, false)
        binding.updateZoomText(currentZoomRatio)
    }

    private fun applyChangeLight() {
        if (::camera.isInitialized && !isFrozen) {
            camera.cameraControl.enableTorch(isLight)
        }
    }

    private fun toggleFreeze() {
        if (lastRawBitmap == null) return

        isFrozen = !isFrozen

        if (isFrozen) {
            // Zeige eingefrorenes Bild
            val bitmapToDisplay = if (isInverted) invertColors(lastRawBitmap!!) else lastRawBitmap
            binding.processedImageView.setImageBitmap(bitmapToDisplay)
            binding.processedImageView.visibility = View.VISIBLE
            binding.viewFinder.visibility = View.INVISIBLE

            // 🔒 Deaktiviere alle Steuerelemente
            binding.zoomSlider.isEnabled = false
            binding.lightToggle.isEnabled = false
            binding.invertToggle.isEnabled = false

        } else {
            // 🔓 Aktiviere alle Steuerelemente
            binding.zoomSlider.isEnabled = true
            binding.lightToggle.isEnabled = true
            binding.invertToggle.isEnabled = true

            // Stelle Sichtbarkeit gemäß aktuellem Modus wieder her
            if (isInverted) {
                binding.processedImageView.visibility = View.VISIBLE
                binding.viewFinder.visibility = View.INVISIBLE
            } else {
                binding.viewFinder.visibility = View.VISIBLE
                binding.processedImageView.visibility = View.GONE
            }

            shouldResetZoom = false
            startCamera()
        }
    }

    private fun startCamera() {
        if (isFrozen) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            // 🔥 Immer ImageAnalysis – aber nur bei Bedarf UI aktualisieren
            imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val bitmap = imageProxyToBitmap(imageProxy)
                            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                            lastRawBitmap = rotated

                            // Nur im Invert-Modus UI aktualisieren
                            if (!isFrozen && isInverted) {
                                val inverted = invertColors(rotated)
                                runOnUiThread {
                                    binding.processedImageView.setImageBitmap(inverted)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Magnifier", "Frame processing failed", e)
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            // Sichtbarkeit
            if (isInverted) {
                binding.viewFinder.visibility = View.INVISIBLE
                binding.processedImageView.visibility = View.VISIBLE
            } else {
                binding.viewFinder.visibility = View.VISIBLE
                binding.processedImageView.visibility = View.GONE
            }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis!!
                )

                if (shouldResetZoom) {
                    currentZoomRatio = 1.0f
                }
                camera.cameraControl.setZoomRatio(currentZoomRatio)
                camera.cameraControl.enableTorch(isLight)
                shouldResetZoom = false

                runOnUiThread {
                    updateZoomUI()
                }

            } catch (exc: Exception) {
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
                Log.e("Magnifier", "Camera binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val planes = imageProxy.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun invertColors(bitmap: Bitmap): Bitmap {
        val inverted = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inverted)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                set(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                ))
            })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return inverted
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (::camera.isInitialized && !isFrozen) {
            camera.cameraControl.setZoomRatio(currentZoomRatio)
            camera.cameraControl.enableTorch(isLight)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}