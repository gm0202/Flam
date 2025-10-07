package com.example.edgeviewer

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var toggleMode: SwitchMaterial
    private lateinit var fpsText: TextView
    private lateinit var btnStartStop: Button
    
    private var isProcessing = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Set up edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // Initialize views
        glSurfaceView = findViewById(R.id.glSurface)
        toggleMode = findViewById(R.id.toggleMode)
        fpsText = findViewById(R.id.fpsText)
        btnStartStop = findViewById(R.id.btnStartStop)
        
        // Set up UI listeners
        setupUI()
        
        // Request camera permission if not granted
        if (!hasCameraPermission()) {
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
            // Toggle between raw and edge detection modes
            if (hasCameraPermission()) {
                setEdgeDetectionMode(isChecked)
            }
        }
    }
    
    private fun startProcessing() {
        Log.d(TAG, "Starting camera processing")
        initializeCamera()
        startCameraPreview()
    }
    
    private fun stopProcessing() {
        Log.d(TAG, "Stopping camera processing")
        stopCameraPreview()
    }
    
    private fun updateUI() {
        btnStartStop.text = if (isProcessing) getString(R.string.stop) else getString(R.string.start)
    }
    
    private fun setEdgeDetectionMode(enabled: Boolean) {
        // TODO: Implement edge detection mode change
        Log.d(TAG, "Edge detection mode: $enabled")
    }
    
    private fun initializeCamera() {
        // Initialize camera and related components
        glSurfaceView.setEGLContextClientVersion(2)
        // TODO: Set up renderer
        // glSurfaceView.setRenderer(MyGLRenderer())
    }
    
    private fun startCameraPreview() {
        glSurfaceView.onResume()
    }
    
    private fun stopCameraPreview() {
        glSurfaceView.onPause()
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
                initializeCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required for this app to function",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            if (isProcessing) {
                startProcessing()
            } else {
                glSurfaceView.onResume()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (isProcessing) {
            stopProcessing()
        } else {
            glSurfaceView.onPause()
        }
    }
    
    // Function to update FPS on UI thread
    private fun updateFps(fps: Int) {
        runOnUiThread {
            fpsText.text = "FPS: $fps"
        }
    }
    
    // Load native library
    init {
        System.loadLibrary("native-lib")
    }
}
