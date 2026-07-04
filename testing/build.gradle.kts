import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
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

    // Shared SPI conformance suite (e.g. ChannelContract), consumed by adapter modules via
    // testImplementation(testFixtures(project(":testing"))). JUnit5 is pinned because a testFixtures
    // source set has no Test task to drive kotlin-test's framework substitution. The `test` source set
    // auto-depends on these fixtures, so it inherits both deps — no separate testImplementation needed.
    testFixturesImplementation(libs.kotlin.test.junit5)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
