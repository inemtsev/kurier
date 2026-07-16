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

// All JavaExec tasks, not just `run` — IntelliJ's gutter arrow generates its own exec task.
// Token loading from .env lives in Main.kt, so it works no matter how the JVM is launched.
tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
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
