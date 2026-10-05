import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    `maven-publish`
}

group = "jp.metamaps"
version = rootProject.version

android {
    namespace = "jp.metamaps.positioning.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${rootProject.version}\"")
        // Compile against the latest Android API, but do not force host apps above the API level that
        // the SDK actually needs (androidx.browser requires 36).
        aarMetadata {
            minCompileSdk = 36
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            merges += "META-INF/NOTICE.txt"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api("jp.metamaps:metamaps-positioning-core:${rootProject.version}")
    // Public APIs expose Flow/coroutines and MapView uses Dispatchers.Main at runtime.
    // Publish the Android dispatcher transitively so a host app does not need to guess this dependency.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.altbeacon:android-beacon-library:2.21.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "metamaps-positioning"
                pom {
                    name.set("Metamaps Positioning for Android")
                    description.set("Optional indoor positioning for the Metamaps SDK, also usable without the map view.")
                    url.set("https://metamaps.jp")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "build"
                url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
            }
        }
    }
}
