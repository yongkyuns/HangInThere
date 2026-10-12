pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HangInThere"
include(":shared")
if (providers.gradleProperty("workout.appleOnly").orNull != "true") include(":android:app")
