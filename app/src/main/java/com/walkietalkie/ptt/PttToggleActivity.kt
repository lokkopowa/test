package com.walkietalkie.ptt

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Scorciatoia "PTT Parla", pensata per i tasti laterali che sanno solo "aprire un'app"
 * (es. Ulefone Armor): ogni pressione accende o spegne la trasmissione, anche a schermo
 * bloccato, senza mostrare nulla. Se non si è in un canale apre l'app.
 */
class PttToggleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        val session = (application as WalkieApp).session
        if (session.started.value) {
            session.toggleTalking()
        } else {
            Toast.makeText(this, "Prima entra in un canale", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
