package com.example.edgeviewer

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    
    // Buffer pooling for zero-allocation processing
    private val nv21BufferPool = ArrayBlockingQueue<ByteArray>(3)
    private val rgbaBufferPool = ArrayBlockingQueue<ByteBuffer>(3)
    private val pendingFrames = AtomicInteger(0)
    private var bufferSize = 0
    private var rgbaBufferSize = 0
    
    private val nativeLib = NativeLib.getInstance()
    
    // Processing mode: 0 = raw, 1 = edge detection
    var processingMode = 1
    
    // GLSurfaceView reference for queueEvent
    var glSurfaceView: GLSurfaceView? = null
    
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
                    // Use acquireLatestImage to drop old frames automatically
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                    }
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
    
    private fun processImage(image: Image) {
        try {
            val width = image.width
            val height = image.height
            
            // Check if we're falling behind (frame dropping)
            val pending = pendingFrames.get()
            if (pending > 2) {
                Log.w(TAG, "Frame processing falling behind, dropping frame (pending: $pending)")
                return
            }
            
            // Initialize buffer pools if needed
            val nv21Size = width * height * 3 / 2
            val rgbaSize = width * height * 4
            
            if (bufferSize != nv21Size) {
                bufferSize = nv21Size
                rgbaBufferSize = rgbaSize
                
                // Initialize NV21 buffer pool
                nv21BufferPool.clear()
                for (i in 0 until 3) {
                    nv21BufferPool.offer(ByteArray(nv21Size))
                }
                
                // Initialize RGBA buffer pool
                rgbaBufferPool.clear()
                for (i in 0 until 3) {
                    rgbaBufferPool.offer(ByteBuffer.allocateDirect(rgbaSize))
                }
                
                Log.d(TAG, "Initialized buffer pools: ${width}x${height}")
            }
            
            // Get buffer from pool (non-blocking)
            val nv21Buffer = nv21BufferPool.poll()
            if (nv21Buffer == null) {
                Log.w(TAG, "No available NV21 buffer, dropping frame")
                return
            }
            
            // Convert YUV_420_888 to NV21
            yuv420ToNv21(image, nv21Buffer)
            
            // Increment pending counter
            pendingFrames.incrementAndGet()
            
            // Process on single-threaded executor
            processingExecutor.execute {
                var rgbaBuffer: ByteBuffer? = null
                try {
                    // Log first frame sent to native
                    if (!firstFrameSent) {
                        Log.d(TAG, "frameSentToNative")
                        firstFrameSent = true
                    }
                    
                    // Call native processing
                    val processedData = nativeLib.processNV21(
                        nv21Buffer,
                        width,
                        height,
                        processingMode
                    )
                    
                    // Get RGBA buffer from pool
                    rgbaBuffer = rgbaBufferPool.poll()
                    if (rgbaBuffer == null) {
                        Log.w(TAG, "No available RGBA buffer, dropping frame")
                        return@execute
                    }
                    
                    // Convert NV21 to RGBA in the pooled buffer
                    rgbaBuffer.clear()
                    for (i in 0 until width * height) {
                        val y = processedData[i].toInt() and 0xFF
                        rgbaBuffer.put(y.toByte())  // R
                        rgbaBuffer.put(y.toByte())  // G
                        rgbaBuffer.put(y.toByte())  // B
                        rgbaBuffer.put(0xFF.toByte()) // A
                    }
                    rgbaBuffer.position(0)
                    
                    // Queue GL update on GL thread
                    val finalRgbaBuffer = rgbaBuffer
                    glSurfaceView?.queueEvent {
                        try {
                            onFrameAvailable?.invoke(processedData, width, height)
                            
                            // Return buffer to pool
                            rgbaBufferPool.offer(finalRgbaBuffer)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in GL thread: ${e.message}", e)
                            rgbaBufferPool.offer(finalRgbaBuffer)
                        }
                    }
                    
                    // Calculate FPS
                    frameCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastFrameTime >= 1000) {
                        val fps = (frameCount * 1000f / (currentTime - lastFrameTime)).toInt()
                        val queueSize = pendingFrames.get()
                        Log.d(TAG, "Processing FPS: $fps, Queue: $queueSize")
                        frameCount = 0
                        lastFrameTime = currentTime
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame: ${e.message}", e)
                    // Return buffer to pool on error
                    rgbaBuffer?.let { rgbaBufferPool.offer(it) }
                } finally {
                    // Return NV21 buffer to pool
                    nv21BufferPool.offer(nv21Buffer)
                    // Decrement pending counter
                    pendingFrames.decrementAndGet()
                }
            }
            
        } finally {
            // Always close the image
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
        return bigEnough ?: choices.maxByOrNull { it.width * it.height } ?: choices[0]
    }
}

// Extension function to convert YUV_420_888 to NV21
private fun Image.toNv21(): ByteArray {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    
    val nv21 = ByteArray(ySize + uSize + vSize)
    
    // Y channel
    yBuffer.get(nv21, 0, ySize)
    
    // Interleave U and V
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    
    return nv21
}
