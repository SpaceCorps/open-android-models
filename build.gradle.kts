// Plugins are declared once here (not applied) so every module shares one
// classloader and one Kotlin Gradle Plugin version. AGP 9 compiles Kotlin in
// Android modules itself (built-in Kotlin) using that same KGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Each library module applies `maven-publish` and declares its publication,
// com.spacecorps.oam:<module name>:<version> (group and version come from
// gradle.properties); the POM they share is filled in here. The same
// group:name coordinates let includeBuild() of this repository stand in for
// the published artifacts.
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name = project.name
                    // Modules set their description after their plugins apply.
                    description = provider { project.description }
                    url = "https://github.com/SpaceCorps/open-android-models"
                    inceptionYear = "2026"
                    licenses {
                        license {
                            name = "MIT License"
                            url = "https://opensource.org/license/mit"
                            distribution = "repo"
                        }
                    }
                    developers {
                        developer {
                            id = "spacecorps"
                            name = "SpaceCorps"
                            organization = "SpaceCorps Technology OÜ"
                            organizationUrl = "https://github.com/SpaceCorps"
                        }
                    }
                    scm {
                        url = "https://github.com/SpaceCorps/open-android-models"
                        connection = "scm:git:https://github.com/SpaceCorps/open-android-models.git"
                        developerConnection = "scm:git:ssh://git@github.com/SpaceCorps/open-android-models.git"
                    }
                }
            }
        }
    }
}
