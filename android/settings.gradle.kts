pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The positioning engine (`jp.metamaps.positioning:metamap-positioning-core`) ships as a prebuilt
// artifact in the local Maven repository `maven-repository/`.
// `-Pmetamap.engineRepository=/absolute/path` reads it from another directory.
val engineRepository = providers.gradleProperty("metamap.engineRepository")
    .map { file(it) }
    .getOrElse(file("maven-repository"))

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (engineRepository.isDirectory) {
            maven {
                url = engineRepository.toURI()
                content { includeGroup("jp.metamaps.positioning") }
            }
        }
    }
}

rootProject.name = "metamap-android-sdk"

include(":metamap-positioning")
include(":metamap-mapview")
include(":samples:compose")
include(":samples:mapviewsample")
include(":samples:view")
