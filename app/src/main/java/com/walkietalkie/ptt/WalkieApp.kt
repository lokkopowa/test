package com.walkietalkie.ptt

import android.app.Application

/** Tiene la sessione per tutta la vita del processo, anche quando la schermata è chiusa. */
class WalkieApp : Application() {

    val session: WalkieSession by lazy { WalkieSession(this) }

    /** true mentre la schermata dell'app è visibile (allora non servono notifiche dei messaggi). */
    @Volatile
    var uiVisible = false
}
