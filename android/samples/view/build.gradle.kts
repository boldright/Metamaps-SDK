import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "jp.metamaps.samples.view"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.metamaps.samples.view"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // AltBeacon's optional BluetoothMedic posts notifications, but Metamaps never invokes it.
        disable += "NotificationPermission"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":metamaps-mapview"))
}
