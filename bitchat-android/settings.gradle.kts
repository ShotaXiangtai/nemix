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
    }
}

rootProject.name = "bitchat-android"
include(":app")
// Wear OS module is not built for nemix (smartphone-only). Its shared sources no longer
// compile against the nemix app code; re-enable only after updating wear/build.gradle.kts.
// include(":wear")
// Using published Arti AAR; local module not included
