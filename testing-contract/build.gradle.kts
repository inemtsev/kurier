import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core"))

    // api, not implementation: the contract's compile surface IS the test framework — subclasses
    // reference kotlin.test's @Test/assertions and runTest directly.
    api(libs.kotlin.test.junit5)
    api(libs.kotlinx.coroutines.test)
}
