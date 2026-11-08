// Google's copy of Maven Central first: Maven Central sends some large jars (the Kotlin compiler)
// on to GitHub downloads, which sometimes time out (504) in CI.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"
pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven(centralMirror); mavenCentral() }
}
rootProject.name = "KankiWarp"
include(":app")
