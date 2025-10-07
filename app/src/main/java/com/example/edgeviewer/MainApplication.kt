package com.example.edgeviewer

import android.app.Application
import android.util.Log

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("EdgeViewer", "Application created")
    }
}
