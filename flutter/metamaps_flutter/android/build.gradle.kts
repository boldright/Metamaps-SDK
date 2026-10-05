group = "jp.metamaps.flutter"
version = "1.0-SNAPSHOT"

val metamapsPluginProjectDirectory = projectDir

// pubspec.yaml is the source of truth for the SDK version. The plugin depends on native libraries of the same
// version, so a version hardcoded here would leave a version-bumped plugin with a dependency it cannot resolve.
val metamapsSdkVersion: String = metamapsPluginProjectDirectory.resolve("../pubspec.yaml")
    .readLines()
    .first { it.startsWith("version:") }
    .substringAfter("version:")
    .trim()

buildscript {
    val kotlinVersion = "2.4.20"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        val configuredRepository = providers
            .gradleProperty("metamapsSdkRepository")
            .orElse(providers.environmentVariable("METAMAPS_MAVEN_URL"))
            .orNull
        val bundledRepository = metamapsPluginProjectDirectory.resolve(
            "maven-repository",
        ).canonicalFile
        val monorepoRepository = metamapsPluginProjectDirectory.resolve(
            "../../../android/build/maven-repository",
        ).canonicalFile
        when {
            configuredRepository != null -> maven(url = configuredRepository)
            bundledRepository.isDirectory -> maven(url = bundledRepository)
            monorepoRepository.isDirectory -> maven(url = monorepoRepository)
        }
    }
}

plugins {
    id("com.android.library")
}

val agpMajor = com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION.substringBefore('.').toInt()

if (agpMajor < 9) {
    apply(plugin = "org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.metamaps.flutter"

    // Flutter 3.44.9 host apps compile against 36 and warn when a plugin compiles against a higher API level.
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
        // Do not force host apps above the API level that the SDK actually needs (androidx.browser requires 36).
        aarMetadata {
            minCompileSdk = 36
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()

                it.outputs.upToDateWhen { false }

                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

project.extensions.configure(org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension::class.java) {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("jp.metamaps:metamaps-mapview:$metamapsSdkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("org.mockito:mockito-core:5.24.0")
}
