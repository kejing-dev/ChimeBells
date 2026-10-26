package io.embodit.bianzhong.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.bianzhong.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ChimeGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
