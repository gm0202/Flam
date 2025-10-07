package com.example.edgeviewer

import android.util.Log

/**
 * Native library loader and JNI interface for EdgeViewer
 */
class NativeLib {
    /**
     * Load the native library when the class is initialized
     */
    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Error loading native library: ${e.message}")
        }
    }

    /**
     * Process NV21 image data using native code
     * @param data The NV21 image data
     * @param width Image width
     * @param height Image height
     * @param mode Processing mode (0 = raw, 1 = edge detection)
     * @return Processed image data
     */
    external fun processNV21(data: ByteArray, width: Int, height: Int, mode: Int): ByteArray

    companion object {
        private const val TAG = "NativeLib"
        
        // Singleton instance
        @Volatile
        private var instance: NativeLib? = null
        
        @JvmStatic
        fun getInstance(): NativeLib {
            return instance ?: synchronized(this) {
                instance ?: NativeLib().also { instance = it }
            }
        }
    }
}
