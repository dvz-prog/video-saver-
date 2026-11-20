package com.videosaver.app

import android.app.Application
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        var ready = false
            private set

        var initError: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            YoutubeDL.getInstance().init(this)
            ready = true
        } catch (e: Exception) {
            initError = e.message
        }
    }
}
