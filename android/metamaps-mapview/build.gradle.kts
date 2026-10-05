import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    `maven-publish`
}

group = "jp.metamaps"
version = rootProject.version

android {
    namespace = "jp.metamaps.mapview"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // Compile against the latest Android API, but do not force host apps above the API level that
        // the SDK actually needs (androidx.browser requires 36).
        aarMetadata {
            minCompileSdk = 36
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    api(project(":metamaps-positioning"))
    implementation("androidx.browser:browser:1.10.0")
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
                artifactId = "metamaps-mapview"
                pom {
                    name.set("Metamaps MapView for Android")
                    description.set("Embeds maps built with Metamaps in Android apps, with optional indoor positioning.")
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
