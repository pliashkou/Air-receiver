package com.airreceiver.tv

import android.app.Application
import android.util.Log

class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("AirPlayApp", "AirReceiver starting")
    }
}
