pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kurier"

include(
    ":core",
    ":runtime",
    ":adapter-telegram",
    ":adapter-discord",
    ":testing",
    ":samples:echo-bot",
)
