package com.example.edgeviewer

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraController(private val context: Context) {
    private val TAG = "CameraController"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val backgroundHandler: Handler
    private val backgroundThread: HandlerThread
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private var frameCount = 0
    private var lastFrameTime = System.currentTimeMillis()
    private var firstFrameSent = false
    
    // Reusable NV21 buffer to avoid allocations every frame
    private var nv21Buffer: ByteArray? = null
    private val nativeLib = NativeLib.getInstance()
    
    // Processing mode: 0 = raw, 1 = edge detection
    var processingMode = 1
    
    // Callback for handling processed image data
    var onFrameAvailable: ((data: ByteArray, width: Int, height: Int) -> Unit)? = null
    
    init {
        // Set up background thread for camera operations
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }
    
    fun startPreview() {
        if (isProcessing.getAndSet(true)) return
        
        try {
            val cameraId = cameraManager.cameraIdList[0] // Use first available camera (back camera)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // Get preview size (1280x720)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = chooseOptimalSize(map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray())
            
            // Create ImageReader for YUV_420_888 format
            imageReader = ImageReader.newInstance(
                previewSize.width, 
                previewSize.height, 
                ImageFormat.YUV_420_888, 
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    processImage(reader.acquireLatestImage())
                }, backgroundHandler)
            }
            
            // Open the camera
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, imageReader!!.surface)
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception: ${e.message}")
            isProcessing.set(false)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            isProcessing.set(false)
        }
    }
    
    fun stopPreview() {
        if (!isProcessing.getAndSet(false)) return
        
        try {
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}")
        }
    }
    
    fun release() {
        stopPreview()
        backgroundThread.quitSafely()
        processingExecutor.shutdown()
    }
    
    private fun createCaptureSession(camera: CameraDevice, surface: Surface) {
        val targets = listOf(surface)
        
        camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                    
                    session.setRepeatingRequest(previewRequest.build(), null, backgroundHandler)
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Failed to start preview: ${e.message}")
                }
            }
            
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure capture session")
            }
        }, backgroundHandler)
    }
    
    private fun processImage(image: Image?) {
        if (image == null) return
        
        try {
            val width = image.width
            val height = image.height
            
            // Initialize reusable buffer if needed
            val bufferSize = width * height * 3 / 2
            if (nv21Buffer == null || nv21Buffer!!.size != bufferSize) {
                nv21Buffer = ByteArray(bufferSize)
                Log.d(TAG, "Allocated NV21 buffer: ${width}x${height}")
            }
            
            // Convert YUV_420_888 to NV21 (reusing buffer)
            yuv420ToNv21(image, nv21Buffer!!)
            
            // Process on executor (not UI thread)
            processingExecutor.execute {
                try {
                    // Log first frame sent to native
                    if (!firstFrameSent) {
                        Log.d(TAG, "frameSentToNative")
                        firstFrameSent = true
                    }
                    
                    // Call native processing
                    val processedData = nativeLib.processNV21(
                        nv21Buffer!!,
                        width,
                        height,
                        processingMode
                    )
                    
                    // Notify callback with processed data
                    onFrameAvailable?.invoke(processedData, width, height)
                    
                    // Calculate FPS
                    frameCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastFrameTime >= 1000) {
                        val fps = (frameCount * 1000f / (currentTime - lastFrameTime)).toInt()
                        Log.d(TAG, "Processing FPS: $fps")
                        frameCount = 0
                        lastFrameTime = currentTime
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame: ${e.message}")
                }
            }
            
        } finally {
            image.close()
        }
    }
    
    /**
     * Convert YUV_420_888 Image to NV21 byte array (reuses buffer)
     */
    private fun yuv420ToNv21(image: Image, nv21: ByteArray) {
        val width = image.width
        val height = image.height
        
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        
        var pos = 0
        
        // Copy Y plane
        if (yRowStride == width) {
            // Optimized path: direct copy
            yBuffer.get(nv21, 0, width * height)
            pos = width * height
        } else {
            // Row by row copy
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }
        
        // Copy UV planes (interleaved as VU for NV21)
        val uvHeight = height / 2
        val uvWidth = width / 2
        
        for (row in 0 until uvHeight) {
            vBuffer.position(row * uvRowStride)
            uBuffer.position(row * uvRowStride)
            
            for (col in 0 until uvWidth) {
                nv21[pos++] = vBuffer.get()
                nv21[pos++] = uBuffer.get()
                
                if (uvPixelStride == 2) {
                    vBuffer.get() // Skip padding
                    uBuffer.get() // Skip padding
                }
            }
        }
    }
    
    private fun chooseOptimalSize(choices: Array<Size>): Size {
        val targetWidth = 1280
        val targetHeight = 720
        
        // Find the smallest size that's at least as big as the target size
        val bigEnough = choices.filter {
            it.width >= targetWidth && it.height >= targetHeight
        }.minByOrNull { it.width * it.height }
        
        // If nothing is big enough, choose the largest available size
        return bigEnough ?: choices.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }
}
