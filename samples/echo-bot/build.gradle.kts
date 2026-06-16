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

tasks.named<JavaExec>("run") {
    standardInput = System.`in`

    // Load .env (gitignored) so `run` picks up TG_TOKEN locally without exporting it.
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
}
