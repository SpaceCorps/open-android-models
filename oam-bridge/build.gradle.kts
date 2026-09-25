plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "JSON-RPC 2.0 bridge (protocol v1.0, shared with open-apple-models) over oam-core agents and oam-game NPCs, decisions and world state."

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    api(project(":oam-core"))
    api(project(":oam-game"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in live checks: AppleConformanceTest (OAM_APPLE_BRIDGE) and ProxyBridgeEvalTest (OAM_PROXY_EVAL).
    val live = listOf("OAM_APPLE_BRIDGE", "OAM_PROXY_EVAL", "OAM_PROXY_URL", "OAM_PROXY_MODEL")
    live.forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    inputs.property("liveChecks", live.joinToString(",") { System.getenv(it) ?: "" })
    if (live.any { !System.getenv(it).isNullOrEmpty() }) outputs.upToDateWhen { false }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = live.any { !System.getenv(it).isNullOrEmpty() }
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
