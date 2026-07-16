import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "kurier.samples.echo.MainKt"
}

// All JavaExec tasks, not just `run`: IntelliJ's gutter arrow generates its own `MainKt.main()`
// exec task that bypasses `run` — without this it silently starts tokenless in console-echo mode.
tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`

    // Load .env (gitignored) so a local run picks up TG_TOKEN etc. without exporting them.
    rootProject.file(".env").takeIf { it.exists() }
        ?.readLines()
        ?.filter { it.isNotBlank() && !it.startsWith("#") && "=" in it }
        ?.forEach { line ->
            val (key, value) = line.split("=", limit = 2)
            environment(key.trim(), value.trim())
        }
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":testing"))
    implementation(project(":adapter-telegram"))
    implementation(project(":adapter-discord"))
    implementation(project(":adapter-matrix"))
    implementation(project(":adapter-twitch"))
    implementation(project(":adapter-slack"))
}
