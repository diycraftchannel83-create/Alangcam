package com.example

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

class RiderCamApp : Application(), CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            // CameraX accepts Android log levels VERBOSE (2) through ERROR (6).
            // Log.ASSERT (7) is out of range and crashes ProcessCameraProvider initialization.
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
    }
}
