package com.wdtt.client

import android.app.Application
import com.wireguard.android.backend.GoBackend

class WdttApplication : Application() {
    lateinit var backend: GoBackend
        private set

    override fun onCreate() {
        super.onCreate()
        backend = GoBackend(this)
        DeployManager.init(this)
    }
}
