package com.example.edgeviewer

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "onCreate: Starting app initialization")
            
            setContentView(R.layout.activity_main)
            
            // Set up edge-to-edge display
            try {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up edge-to-edge display", e)
            }
            
            // Initialize views
            try {
                glSurfaceView = findViewById(R.id.glSurface)
                toggleMode = findViewById(R.id.toggleMode)
                fpsText = findViewById(R.id.fpsText)
                btnStartStop = findViewById(R.id.btnStartStop)
                
                // Initialize CameraController
                cameraController = CameraController(this)
                
                // Set up UI listeners
                setupUI()
                
                // Request camera permission if not granted
                if (!hasCameraPermission()) {
                    requestCameraPermission()
                } else {
                    Log.d(TAG, "Camera permission already granted")
                }
                
                Log.d(TAG, "App initialization completed successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing views", e)
                showErrorAndFinish("Failed to initialize app: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate", e)
            // Try to show error to user before crashing
            try {
                Toast.makeText(this, "App failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (ignored: Exception) {}
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
        cameraController.onFrameAvailable = { data, width, height ->
            // Process frame in native code
            NativeLib.getInstance().processNV21(data, width, height, if (toggleMode.isChecked) 1 else 0)
        }
        
        cameraController.startPreview()
        
        // Initialize OpenGL
        initializeGL()
    }
    
    private fun stopProcessing() {
        Log.d(TAG, "Stopping camera processing")
        cameraController.stopPreview()
    }
    
    private fun updateUI() {
        btnStartStop.text = if (isProcessing) "Stop" else "Start"
    }
    
    private fun setEdgeDetectionMode(enabled: Boolean) {
        // Mode is handled in the frame callback
        Log.d(TAG, "Edge detection mode: ${if (enabled) "Edge" else "Raw"}")
    }
    
    private fun initializeGL() {
        try {
            Log.d(TAG, "Initializing OpenGL")
            
            // Initialize OpenGL ES 2.0 context
            glSurfaceView.setEGLContextClientVersion(2)
            
            // Set up renderer
            // glSurfaceView.setRenderer(MyGLRenderer())
            
            // Set render mode to when dirty (only render when we have new data)
            glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            
            Log.d(TAG, "OpenGL initialization completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing OpenGL", e)
            throw RuntimeException("Failed to initialize OpenGL: ${e.message}", e)
        }
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
        if (isProcessing) {
            cameraController.startPreview()
        }
        glSurfaceView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        cameraController.stopPreview()
        glSurfaceView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraController.release()
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
