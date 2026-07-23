plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.6" 
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
        maven(url = "https://jitpack.io")
    }
    dependencies {
        classpath(libs.gradle.kotlin)
    }
}

subprojects {
    val hasSourceDir = file("src").exists()
    
    if (hasSourceDir) {
        apply(plugin = "io.gitlab.arturbosch.detekt")

        detekt {
            toolVersion = "1.23.6"
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
                html.outputLocation.set(layout.buildDirectory.file("outputs/detekt-report.html").get().asFile)
            }
        }
    }
}
