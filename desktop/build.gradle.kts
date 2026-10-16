import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Versione PC (Windows / macOS / Linux): usa il collegamento internet e parla con l'app Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.moquette:moquette-broker:0.17")
}

compose.desktop {
    application {
        mainClass = "com.walkietalkie.ptt.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "WalkiePTT"
            packageVersion = "1.0.0"
            description = "Walkie talkie PTT, chat e foto con l'app Android Walkie PTT"
            vendor = "Walkie PTT"
            includeAllModules = true

            windows {
                menuGroup = "Walkie PTT"
                shortcut = true
                dirChooser = true
                upgradeUuid = "5b0f6f4e-7a43-4e0e-9b55-6c1d1f2a9e31"
            }
            macOS {
                bundleID = "com.walkietalkie.ptt.desktop"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Serve il microfono per parlare con il tasto PTT.</string>
                    """.trimIndent()
                }
            }
            linux {
                shortcut = true
            }
        }
    }
}
