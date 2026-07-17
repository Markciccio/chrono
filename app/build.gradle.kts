import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("signing.properties")
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

android {
    namespace = "it.crono"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.crono"
        // GPS, WebView/OSM and Android TTS used by Pit Engineer are available from
        // Android 6.0 onward; keeping this low produces one APK usable on many phones.
        minSdk = 23
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                    storePassword = signingProperties.getProperty("storePassword")
                    keyAlias = signingProperties.getProperty("keyAlias")
                    keyPassword = signingProperties.getProperty("keyPassword")
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
