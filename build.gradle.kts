import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

apiValidation {
    // The sample is not a published library — everything else gets an api/<module>.api baseline,
    // and apiCheck (hooked into `check`) fails the build on any public-ABI change.
    ignoredProjects += listOf("echo-bot")
}

allprojects {
    group = "com.eventslooped"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
}
