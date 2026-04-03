pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Samsung Health SDK is not on Maven — add the local AAR via flatDir (see SETUP.md)
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "WorkoutLogger"
include(":app")
