pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The positioning engine (`jp.metamaps:metamaps-positioning-core`) ships as a prebuilt
// artifact in the local Maven repository `maven-repository/`.
// `-Pmetamaps.engineRepository=/absolute/path` reads it from another directory.
val engineRepository = providers.gradleProperty("metamaps.engineRepository")
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
                content { includeGroup("jp.metamaps") }
            }
        }
    }
}

rootProject.name = "metamaps-android-sdk"

include(":metamaps-positioning")
include(":metamaps-mapview")
include(":samples:compose")
include(":samples:view")
