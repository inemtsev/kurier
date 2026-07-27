import com.vanniktech.maven.publish.MavenPublishBaseExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish) apply false
}

apiValidation {
    // The sample is not a published library — everything else gets an api/<module>.api baseline,
    // and apiCheck (hooked into `check`) fails the build on any public-ABI change.
    ignoredProjects += listOf("echo-bot")
}

allprojects {
    group = "com.eventslooped"
    version = "0.1.0"
}

// One-line, consumer-facing POM description per published module.
val moduleDescriptions = mapOf(
    "core" to "Core API and adapter SPI — one typed, coroutine/Flow-based API for chat platforms",
    "runtime" to "Gateway runtime: the chatGateway {} DSL, adapter supervision, and flow merging",
    "adapter-telegram" to "Telegram adapter (Bot API long-polling over Ktor)",
    "adapter-discord" to "Discord adapter (wraps Kord)",
    "adapter-matrix" to "Matrix adapter (Trixnity, /sync long-poll)",
    "adapter-twitch" to "Twitch adapter (EventSub WebSocket + Helix over Ktor)",
    "adapter-slack" to "Slack adapter (Socket Mode via the official Slack SDK)",
    "testing" to "In-memory FakeAdapter/FakeChannel for unit-testing bots",
    "testing-contract" to "Shared SPI conformance suite and rendering-matrix samples for adapter authors",
)

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    // Everything except the samples publishes to Maven Central via the Central Portal.
    if (path.startsWith(":samples")) return@subprojects
    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        // Central rejects unsigned uploads, but gating on the key keeps keyless local/CI
        // publishes (publishToMavenLocal) working without a GPG setup.
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        coordinates("com.eventslooped", "kurier-$name", version.toString())
        pom {
            name.set("kurier-${this@subprojects.name}")
            description.set(moduleDescriptions.getValue(this@subprojects.name))
            url.set("https://github.com/inemtsev/kurier")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("inemtsev")
                    name.set("Ilya Nemtsev")
                }
            }
            scm {
                url.set("https://github.com/inemtsev/kurier")
                connection.set("scm:git:https://github.com/inemtsev/kurier.git")
                developerConnection.set("scm:git:ssh://git@github.com/inemtsev/kurier.git")
            }
        }
    }
}
