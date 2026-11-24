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
        maven { url = uri("https://guardianproject.github.io/gpmaven/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BLACKSTRINGS"
include(":app")
