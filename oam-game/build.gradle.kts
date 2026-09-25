plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Game AI over oam-core: personas, NPC dialogue with memory and streaming, decisions, world state and content generation."

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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The live proxy eval (GameProxyEvalTest) only runs when OAM_PROXY_EVAL=1.
    listOf("OAM_PROXY_EVAL", "OAM_PROXY_EVAL_RUNS", "OAM_PROXY_EVAL_ONLY", "OAM_PROXY_URL", "OAM_PROXY_MODEL").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    inputs.property("proxyEval", System.getenv("OAM_PROXY_EVAL") ?: "")
    // A live eval always runs: its result depends on the model, not on the inputs.
    if (System.getenv("OAM_PROXY_EVAL") == "1") outputs.upToDateWhen { false }
    // ReadmeExamplesTest compares the README's Kotlin examples with their tested copies (here and
    // in oam-mlkit) and its OamJni outline with OamJni.kt, so a change to any of them reruns it.
    inputs.files(
        rootProject.file("README.md"),
        file("src/test/kotlin/com/spacecorps/oam/readme/ReadmeExamplesTest.kt"),
        rootProject.file("oam-mlkit/src/test/kotlin/com/spacecorps/oam/readme/ReadmeAndroidExamples.kt"),
        rootProject.file("oam-mlkit/src/main/kotlin/com/spacecorps/oam/jni/OamJni.kt"),
    ).withPropertyName("readmeExamples").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed", "skipped")
        showStandardStreams = System.getenv("OAM_PROXY_EVAL") == "1"
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
