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
    implementation(libs.slack.api.client)
    implementation(libs.java.websocket) // the Socket Mode WebSocket backend; the SDK marks it `provided`

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":testing-contract")) // shared SPI ChannelContract + rendering matrix
}

tasks.withType<Test> {
    useJUnitPlatform()
}
