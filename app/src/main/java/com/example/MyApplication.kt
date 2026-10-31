package com.example

import android.app.Application
import android.os.Build
import android.webkit.WebView

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val processName = getProcessName()
                if (processName != packageName) {
                    WebView.setDataDirectorySuffix(processName)
                }
            } catch (_: Exception) {}
        }
    }
}
