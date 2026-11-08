package com.walkietalkie.ptt.hw

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servizio di accessibilità usato SOLO per ricevere il tasto PTT fisico anche con l'app ridotta
 * o lo schermo spento (come fanno le app PTT professionali). Non legge lo schermo né i contenuti:
 * guarda solo il tasto assegnato e lascia passare tutti gli altri.
 */
class PttKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        HardwarePtt.load(this)
        HardwarePtt.accessibilityActive.value = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = HardwarePtt.onKey(this, event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        HardwarePtt.accessibilityActive.value = false
        return super.onUnbind(intent)
    }
}
