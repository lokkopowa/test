package com.walkietalkie.ptt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano attivo finché si è in un canale: con l'app ridotta o lo schermo
 * spento continua a ricevere voce, messaggi e foto, e permette di parlare dalla notifica.
 */
class WalkieService : Service() {

    companion object {
        private const val CHANNEL_ONGOING = "walkie_ongoing"
        private const val CHANNEL_MESSAGES = "walkie_messages"
        private const val ID_ONGOING = 1
        private const val ID_MESSAGES = 2
        private const val ACTION_TALK = "com.walkietalkie.ptt.TALK"
        private const val ACTION_EXIT = "com.walkietalkie.ptt.EXIT"

        /** Dalla notifica si parla "a interruttore": per sicurezza la trasmissione si chiude da sola. */
        private const val MAX_TALK_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as WalkieApp
    private val session get() = app.session
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var talkTimeout: Job? = null
    private var lastNotifiedId = 0L
    private val unread = ArrayList<String>()
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi") // ServiceCompat ignora i tipi sulle versioni che non li conoscono
    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Su Android 14 il tipo "microphone" va dichiarato all'avvio, mentre l'app è in primo piano:
        // è ciò che permette di parlare anche con l'app ridotta.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        ServiceCompat.startForeground(this, ID_ONGOING, buildOngoing("Collegamento…", false), types)
        acquireLocks()

        lastNotifiedId = session.messages.value.lastOrNull()?.id ?: 0L
        scope.launch {
            combine(session.peers, session.speaking, session.transmitting) { peers, speaking, talking ->
                Triple(peers, speaking, talking)
            }.collect { (peers, speaking, talking) ->
                val text = when {
                    talking -> "🎙️ In trasmissione…"
                    speaking.isNotEmpty() -> "🔊 ${speaking.joinToString()} sta parlando…"
                    peers.isEmpty() -> "Canale ${session.channelName} · nessuno connesso"
                    else -> "Canale ${session.channelName} · ${peers.size} connessi: " +
                        peers.values.joinToString { it.nick }
                }
                notify(ID_ONGOING, buildOngoing(text, talking))
            }
        }
        scope.launch(Dispatchers.Default) { // decodificare le foto non deve bloccare il thread principale
            session.messages.collect { list -> notifyNewMessages(list) }
        }
        scope.launch {
            // Uscita dal canale (dall'app o dalla notifica): il servizio si chiude. stopSelf(id)
            // non lo chiude se nel frattempo si è rientrati in un canale (nuovo avvio).
            session.started.collect { if (!it) stopSelf(lastStartId) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_TALK -> toggleTalk()
            ACTION_EXIT -> session.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        talkTimeout?.cancel()
        session.stopTalking()
        scope.cancel()
        releaseLocks()
        NotificationManagerCompat.from(this).cancel(ID_MESSAGES)
        super.onDestroy()
    }

    /** Chiudere l'app dalle "recenti" non deve lasciare il canale aperto senza schermata. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session.stop()
        super.onTaskRemoved(rootIntent)
    }

    private fun toggleTalk() {
        if (session.transmitting.value) {
            talkTimeout?.cancel()
            session.stopTalking()
        } else {
            session.startTalking()
            talkTimeout?.cancel()
            talkTimeout = scope.launch {
                delay(MAX_TALK_MS)
                session.stopTalking()
            }
        }
    }

    // ---------------------------------------------------------------- notifiche

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Walkie attivo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra il canale e chi sta parlando mentre l'app è ridotta"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messaggi e foto", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messaggi e foto ricevuti mentre l'app è ridotta"
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, WalkieService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildOngoing(text: String, talking: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_walkie)
            .setContentTitle("Walkie PTT")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppIntent())
            .addAction(0, if (talking) "⏹ Stop" else "🎙️ Parla", serviceIntent(ACTION_TALK, 1))
            .addAction(0, "Esci dal canale", serviceIntent(ACTION_EXIT, 2))
            .build()

    private fun notifyNewMessages(list: List<ChatItem>) {
        val fresh = list.filter { it.id > lastNotifiedId }
        if (fresh.isEmpty()) return
        lastNotifiedId = fresh.last().id
        if (app.uiVisible) {
            unread.clear()
            NotificationManagerCompat.from(this).cancel(ID_MESSAGES)
            return
        }
        var lastPhoto: ChatItem.Photo? = null
        for (item in fresh) {
            when (item) {
                is ChatItem.Text -> if (!item.mine) unread += "${item.from}: ${item.text}"
                is ChatItem.Photo -> if (!item.mine) {
                    unread += "${item.from}: 📷 Foto"
                    lastPhoto = item
                }
                is ChatItem.System -> Unit
            }
        }
        if (unread.isEmpty()) return
        val builder = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_walkie)
            .setContentTitle("Canale ${session.channelName}")
            .setContentText(unread.last())
            .setNumber(unread.size)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent())
        val photo = lastPhoto?.let { PhotoUtils.decodeSampled(it.file, 1024) }
        if (photo != null) {
            builder.setLargeIcon(photo)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(photo).setSummaryText(unread.last()))
        } else {
            builder.setStyle(NotificationCompat.InboxStyle().also { s -> unread.takeLast(6).forEach(s::addLine) })
        }
        notify(ID_MESSAGES, builder.build())
    }

    /** Senza il permesso notifiche (Android 13+) il servizio funziona lo stesso, senza avvisi. */
    @SuppressLint("MissingPermission")
    private fun notify(id: Int, notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(id, notification)
        }
    }

    // ---------------------------------------------------------------- CPU e Wi-Fi accesi

    // Il wakelock dura quanto la sessione e viene rilasciato in onDestroy: un timeout fisso
    // farebbe smettere di ricevere a metà.
    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalkiePTT:session")
            .apply { acquire() }
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiMode, "WalkiePTT:session")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }
}
