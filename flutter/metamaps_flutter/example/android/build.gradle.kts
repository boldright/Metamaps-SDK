val exampleAndroidProjectDirectory = projectDir

// A host app must register the repository that holds the plugin's Android libraries. Use the libraries
// bundled in the plugin (as in the public repository), or the SDK built from source with
// `./gradlew publishSdkToBuildRepository` in `android/`.
val metamapsSdkRepository = listOf(
    "../../android/maven-repository",
    "../../../../android/build/maven-repository",
).map { exampleAndroidProjectDirectory.resolve(it).canonicalFile }.firstOrNull { it.isDirectory }

allprojects {
    repositories {
        google()
        mavenCentral()
        metamapsSdkRepository?.let { maven { url = uri(it) } }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
