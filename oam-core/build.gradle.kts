plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Agentic tool calling for small on-device language models: JSON Schema, the prompt-envelope tool loop, structured output and test/dev models."

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The live proxy eval (ProxyEvalTest) only runs when OAM_PROXY_EVAL=1.
    listOf("OAM_PROXY_EVAL", "OAM_PROXY_EVAL_RUNS", "OAM_PROXY_URL", "OAM_PROXY_MODEL").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    inputs.property("proxyEval", System.getenv("OAM_PROXY_EVAL") ?: "")
    // A live eval always runs: its result depends on the model, not on the inputs.
    if (System.getenv("OAM_PROXY_EVAL") == "1") outputs.upToDateWhen { false }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = System.getenv("OAM_PROXY_EVAL") == "1"
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
