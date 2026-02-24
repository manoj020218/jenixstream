pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // FFmpegKit community fork - maintained after original was retired Jan 2025
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "JenixStream"
include(":app")
