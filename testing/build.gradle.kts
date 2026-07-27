import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Explicit: interface default methods compile as JVM default methods (with DefaultImpls compat
        // bridges), so SPI interfaces can gain members without breaking compiled implementors.
        jvmDefault.set(JvmDefaultMode.ENABLE)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Deliberately core-only: kurier-testing stays framework-free (no JUnit on consumers'
    // classpaths) and KMP-promotable. The JUnit5-bound conformance suite lives in :testing-contract.
    api(project(":core"))

    testImplementation(project(":testing-contract")) // FakeChannel proves the shared ChannelContract
}

tasks.withType<Test> {
    useJUnitPlatform()
}
