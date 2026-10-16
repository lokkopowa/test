import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Codice comune ad app Android e versione PC: protocollo, cifratura, codec voce, MQTT.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.moquette:moquette-broker:0.17")
    // Su Android PBKDF2/AES sono forniti da BouncyCastle: i test lo usano per comportarsi uguale.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
