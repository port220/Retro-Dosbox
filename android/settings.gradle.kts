pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // jitpack: usb-serial-for-android (mik3y) is published there, not on
    // Maven Central. Needed for FT232R access to the Port220 Service Module.
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "Retro-DOS"
include(":app")
