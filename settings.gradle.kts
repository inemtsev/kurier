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
    ":adapter-matrix",
    ":adapter-twitch",
    ":adapter-slack",
    ":testing",
    ":samples:echo-bot",
)
