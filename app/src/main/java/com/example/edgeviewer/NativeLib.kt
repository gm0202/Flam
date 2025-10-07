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
     * A native method that is implemented by the 'native-lib' native library
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        private const val TAG = "NativeLib"
        
        // Singleton instance
        @Volatile
        private var instance: NativeLib? = null
        
        fun getInstance(): NativeLib {
            return instance ?: synchronized(this) {
                instance ?: NativeLib().also { instance = it }
            }
        }
    }
}
