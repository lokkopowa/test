package com.walkietalkie.ptt.net

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Collegamento via internet (rete cellulare o Wi-Fi con internet) attraverso un broker MQTT.
 * Tutti i telefoni del canale pubblicano e ascoltano lo stesso topic; i contenuti sono cifrati.
 * Codice Java puro (niente API Android), così è testabile sul PC.
 */
class InternetLink(
    private val brokerUrl: String,
    private val crypto: ChannelCrypto,
    clientId: String,
    private val listener: Listener,
) {
    enum class State { CONNECTING, ONLINE, ERROR, OFF }

    interface Listener {
        fun onState(state: State, detail: String? = null)
        fun onConnected()
        fun onPacket(packet: Packet)
    }

    private val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
    private val retry = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var closed = false

    val isOnline: Boolean get() = !closed && client.isConnected

    /** [will] viene pubblicato dal broker se il telefono sparisce senza salutare. */
    fun connect(will: Packet) {
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 20
            connectionTimeout = 15
            maxInflight = 500
            setWill(crypto.topic, crypto.encrypt(will.encode()), 1, false)
        }
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // Con clean session bisogna ri-sottoscriversi a ogni (ri)connessione.
                runCatching {
                    client.subscribe(crypto.topic, 1, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            listener.onState(State.ONLINE)
                            listener.onConnected()
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            listener.onState(State.ERROR, exception?.message)
                        }
                    })
                }
            }

            override fun connectionLost(cause: Throwable?) {
                if (!closed) listener.onState(State.CONNECTING, cause?.message)
            }

            override fun messageArrived(topic: String?, message: MqttMessage) {
                val plain = crypto.decrypt(message.payload) ?: return
                val packet = Packet.decode(plain) ?: return
                runCatching { listener.onPacket(packet) }
                    .onFailure { log.log(Level.WARNING, "onPacket", it) }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        attempt(options)
    }

    private fun attempt(options: MqttConnectOptions) {
        if (closed) return
        listener.onState(State.CONNECTING)
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    // La riconnessione automatica di Paho vale solo dopo il primo collegamento
                    // riuscito: finché non c'è rete riproviamo noi.
                    if (closed) return
                    listener.onState(State.ERROR, exception?.message)
                    retry.schedule({ attempt(options) }, RETRY_SECONDS, TimeUnit.SECONDS)
                }
            })
        } catch (e: Exception) {
            listener.onState(State.ERROR, e.message)
            retry.schedule({ attempt(options) }, RETRY_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * La voce viaggia con QoS 0 ("spara e dimentica": meglio un piccolo buco che un ritardo),
     * tutto il resto con QoS 1: i broker possono scartare i QoS 0 quando il destinatario è
     * intasato (es. mentre riceve una foto), mentre i QoS 1 vengono sempre consegnati.
     * Gli eventuali doppioni QoS 1 vengono scartati dal ViewModel tramite l'id messaggio.
     */
    fun publish(packet: Packet): Boolean {
        if (!isOnline) return false
        val qos = if (packet is Packet.Audio) 0 else 1
        return try {
            client.publish(crypto.topic, crypto.encrypt(packet.encode()), qos, false)
            true
        } catch (e: Exception) {
            log.log(Level.WARNING, "publish", e)
            false
        }
    }

    fun close(goodbye: Packet?) {
        if (closed) return
        if (goodbye != null) publish(goodbye)
        closed = true
        retry.shutdownNow()
        Thread {
            runCatching { if (client.isConnected) client.disconnect(1000).waitForCompletion(1500) }
            runCatching { client.close(true) }
        }.start()
        listener.onState(State.OFF)
    }

    private companion object {
        const val RETRY_SECONDS = 5L
        val log: Logger = Logger.getLogger("InternetLink")
    }
}
