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
    // Condition de sécurité : On n'applique Detekt que si le sous-module possède un dossier 'src'
    val hasSourceDir = file("src").exists()
    
    if (hasSourceDir) {
        apply(plugin = "io.gitlab.arturbosch.detekt")

        detekt {
            toolVersion = "1.23.6"
            
            // Configuration dynamique de la source : s'adapte que le code soit dans 'src/' ou 'src/main/kotlin/'
            source.setFrom(
                fileTree("src") {
                    include("**/*.kt")
                    exclude("**/resources/**")
                    exclude("**/build/**")
                }
            )
            
            config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
            buildUponDefaultConfig = true
            allRules = false
            ignoreFailures = true // Permet de collecter les rapports de TOUTES les extensions sans bloquer
        }

        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            reports {
                html.required.set(true)
                xml.required.set(false)
                txt.required.set(true)

                // Génère un rapport isolé dans le dossier build de chaque extension active
                html.outputLocation.set(layout.buildDirectory.file("outputs/detekt-report.html").get().asFile)
            }
        }
    }
}
