package com.omnidoc.app

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow wallpaper colours on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Initialise the PDF engine's bundled fonts/resources
        PDFBoxResourceLoader.init(applicationContext)
    }
}
