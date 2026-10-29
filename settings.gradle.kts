pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Google's Home kit, unlocked by the build only when the HOME_SDK_KEY secret is set
        val homeSdk = File(settingsDir, "homesdk")
        if (homeSdk.exists()) maven { url = homeSdk.toURI() }
    }
}
rootProject.name = "jarvis-android"
include(":app")
