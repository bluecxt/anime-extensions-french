import org.gradle.api.artifacts.VersionCatalogsExtension

buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)

    alias(kei.plugins.spotless)
}

val buildLogic: IncludedBuild = gradle.includedBuild("build-logic")
tasks {
    listOf("clean", "spotlessApply", "spotlessCheck").forEach { task ->
        named(task) {
            dependsOn(buildLogic.task(":$task"))
        }
    }
}

subprojects {
    val hasSourceDir = file("src").exists()
    
    if (hasSourceDir) {
        apply(plugin = "io.gitlab.arturbosch.detekt")

        val catalog = rootProject.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val detektVersion = catalog.findVersion("detekt").get().toString()

        detekt {
            toolVersion = detektVersion
            source.setFrom(
                fileTree("src") {
                    include("**/*.kt")
                    exclude("**/resources/**")
                    exclude("**/build/**")
                }
            )
            
            config.setFrom(files("${rootProject.projectDir}/config/detekt.yml"))
            buildUponDefaultConfig = true
            allRules = false
            ignoreFailures = true
        }

        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            reports {
                html.required.set(true)
                xml.required.set(false)
                txt.required.set(true)
                sarif.required.set(true)
            }
        }
    }
}
