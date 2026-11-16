package com.novatv.player

import android.app.Application

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
