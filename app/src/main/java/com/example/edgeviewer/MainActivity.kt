package com.example.edgeviewer

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_CODE = 100
    }
    
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var toggleMode: ToggleButton
    private lateinit var fpsText: TextView
    private lateinit var btnStartStop: Button
    
    private var isProcessing = false
    private lateinit var cameraController: CameraController
    private lateinit var edgeRenderer: EdgeRenderer
    
    // FPS tracking
    private var frameCount = 0
    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsUpdateRunnable = object : Runnable {
        override fun run() {
            fpsText.text = "FPS: $frameCount"
            frameCount = 0
            fpsHandler.postDelayed(this, 1000)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            Log.d(TAG, "onCreate: Starting app initialization")
            
            setContentView(R.layout.activity_main)
            Log.d(TAG, "Layout inflated successfully")
            
            // Initialize views
            glSurfaceView = findViewById(R.id.glSurface)
            toggleMode = findViewById(R.id.toggleMode)
            fpsText = findViewById(R.id.fpsText)
            btnStartStop = findViewById(R.id.btnStartStop)
            
            // Initialize FPS text
            fpsText.text = "FPS: 0"
            
            Log.d(TAG, "Views initialized")
            
            // Initialize CameraController
            cameraController = CameraController(this)
            Log.d(TAG, "CameraController initialized")
            
            // Initialize EdgeRenderer
            edgeRenderer = EdgeRenderer()
            Log.d(TAG, "EdgeRenderer initialized")
            
            // Set up GLSurfaceView
            glSurfaceView.setEGLContextClientVersion(2)
            glSurfaceView.setRenderer(edgeRenderer)
            glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            Log.d(TAG, "GLSurfaceView configured")
            
            // Pass GLSurfaceView reference to CameraController for queueEvent
            cameraController.glSurfaceView = glSurfaceView
            
            // Set up UI listeners
            setupUI()
            Log.d(TAG, "UI listeners set up")
            
            // Request camera permission if not granted
            if (!hasCameraPermission()) {
                Log.d(TAG, "Requesting camera permission")
                requestCameraPermission()
            } else {
                Log.d(TAG, "Camera permission already granted")
            }
            
            Log.d(TAG, "App initialization completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun setupUI() {
        btnStartStop.setOnClickListener {
            if (hasCameraPermission()) {
                isProcessing = !isProcessing
                updateUI()
                
                if (isProcessing) {
                    startProcessing()
                } else {
                    stopProcessing()
                }
            } else {
                requestCameraPermission()
            }
        }
        
        toggleMode.setOnCheckedChangeListener { _, isChecked ->
            setEdgeDetectionMode(isChecked)
        }
    }
    
    private fun startProcessing() {
        Log.d(TAG, "Starting camera processing")
        
        // Initialize camera preview
        cameraController.onFrameAvailable = { processedData, width, height ->
            // Convert processed NV21 to RGBA for display
            val rgbaBuffer = nv21ToRgba(processedData, width, height)
            
            // Update renderer with RGBA data
            edgeRenderer.updateFrame(rgbaBuffer, width, height)
            
            // Increment frame counter for FPS
            frameCount++
        }
        
        // Start FPS counter
        fpsHandler.post(fpsUpdateRunnable)
        
        // Start camera
        cameraController.startPreview()
    }
    
    /**
     * Convert NV21 to RGBA ByteBuffer
     */
    private fun nv21ToRgba(nv21: ByteArray, width: Int, height: Int): java.nio.ByteBuffer {
        val rgbaSize = width * height * 4
        val rgbaBuffer = java.nio.ByteBuffer.allocateDirect(rgbaSize)
        
        // Simple conversion: just use Y channel for grayscale
        for (i in 0 until width * height) {
            val y = nv21[i].toInt() and 0xFF
            rgbaBuffer.put(y.toByte())  // R
            rgbaBuffer.put(y.toByte())  // G
            rgbaBuffer.put(y.toByte())  // B
            rgbaBuffer.put(0xFF.toByte()) // A
        }
        
        rgbaBuffer.position(0)
        return rgbaBuffer
    }
    
    private fun stopProcessing() {
        Log.d(TAG, "Stopping camera processing")
        
        // Stop FPS counter
        fpsHandler.removeCallbacks(fpsUpdateRunnable)
        fpsText.text = "FPS: 0"
        frameCount = 0
        
        // Stop camera
        cameraController.stopPreview()
    }
    
    private fun updateUI() {
        btnStartStop.text = if (isProcessing) "Stop" else "Start"
    }
    
    private fun setEdgeDetectionMode(enabled: Boolean) {
        // Update processing mode in camera controller
        cameraController.processingMode = if (enabled) 1 else 0
        Log.d(TAG, "Edge detection mode: ${if (enabled) "Edge" else "Raw"}")
    }
    
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
                if (isProcessing) {
                    startProcessing()
                }
            } else {
                // Permission denied
                showPermissionDeniedDialog()
            }
        }
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Required")
            .setMessage("This app needs camera permission to function properly.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestCameraPermission()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                isProcessing = false
                updateUI()
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        
        // Resume GL rendering
        glSurfaceView.onResume()
        
        // Resume camera if we were processing and have permission
        if (isProcessing && hasCameraPermission()) {
            Log.d(TAG, "Resuming camera preview")
            cameraController.startPreview()
            fpsHandler.post(fpsUpdateRunnable)
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        
        // Stop FPS counter
        fpsHandler.removeCallbacks(fpsUpdateRunnable)
        
        // Stop camera
        if (isProcessing) {
            cameraController.stopPreview()
        }
        
        // Pause GL rendering
        glSurfaceView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        
        // Stop FPS counter
        fpsHandler.removeCallbacks(fpsUpdateRunnable)
        
        // Release resources
        cameraController.release()
        edgeRenderer.release()
    }
    
    private fun showErrorAndFinish(message: String) {
        Log.e(TAG, message)
        runOnUiThread {
            try {
                AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing error dialog", e)
                finish()
            }
        }
    }
    
    // Native function declarations
    private external fun processFrame(data: ByteArray, width: Int, height: Int, mode: Int)
    
    // Load native library
    init {
        System.loadLibrary("native-lib")
    }
}
