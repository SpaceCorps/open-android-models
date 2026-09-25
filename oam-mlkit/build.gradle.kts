plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

description = "Gemini Nano through the ML Kit GenAI Prompt API as an oam-core LanguageModel, and OamJni, the JNI entry point that runs oam-bridge for native hosts."

android {
    namespace = "com.spacecorps.oam.mlkit"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // JVM unit tests touch android.util.Log and android.content.Context stubs.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        // ML Kit GenAI only ships betas; newer-version checks are not actionable here.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // AGP creates the "release" component after the build script runs.
            afterEvaluate { from(components["release"]) }
        }
    }
}

dependencies {
    api(project(":oam-core"))
    api(project(":oam-bridge"))
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Opt-in real-JNI check (JniProbeTest): a native host library built from src/test/native/probe_host.c.
    val probe = listOf("OAM_JNI_PROBE_LIB", "OAM_JNI_PROBE_OUT")
    probe.forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    inputs.property("jniProbe", probe.joinToString(",") { System.getenv(it) ?: "" })
    if (!System.getenv("OAM_JNI_PROBE_LIB").isNullOrEmpty()) outputs.upToDateWhen { false }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
