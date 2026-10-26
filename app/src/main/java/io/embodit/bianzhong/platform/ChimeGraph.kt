package io.embodit.bianzhong.platform

import android.content.Context
import io.embodit.bianzhong.domain.usecase.RackPose
import io.embodit.bianzhong.platform.input.ControllerInput
import io.embodit.bianzhong.platform.sense.PlaneTracker
import io.embodit.bianzhong.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `ChimeScene.attach` / `detach`).
 */
object ChimeGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    /**
     * Set by the running scene: the rack pose 2.5 m ahead of the viewer, facing them (debug `place=auto`).
     * Null while no scene runs.
     */
    @Volatile var autoPlacement: ((Boolean) -> RackPose?)? = null
}
