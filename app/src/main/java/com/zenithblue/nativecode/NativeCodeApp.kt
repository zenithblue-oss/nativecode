package com.zenithblue.nativecode

import android.app.Application

class NativeCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strs.app = applicationContext
    }
}
