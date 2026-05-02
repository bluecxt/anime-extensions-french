plugins {
    id("com.android.library")
    id("kotlinx-serialization")
}

android {
    compileSdk = AndroidConfig.compileSdk

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "eu.kanade.tachiyomi.lib.${project.name}"

    androidResources.enable = false
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
    implementation(project(":core"))
}

tasks.register("printDependentExtensions") {
    doLast {
        project.printDependentExtensions()
    }
}
