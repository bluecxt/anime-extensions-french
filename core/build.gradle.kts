import java.util.Properties

plugins {
    id("com.android.library")
    id("keiyoushi.lint")
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
        consumerProguardFiles("consumer-rules.pro")

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }
        val tmdbApi = System.getenv("TMDB_API") ?: localProperties.getProperty("TMDB_API", "")
        buildConfigField("String", "TMDB_API", "\"$tmdbApi\"")
    }

    namespace = "keiyoushi.core"

    buildFeatures {
        resValues = false
        shaders = false
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.10.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    compileOnly("io.mockk:mockk:1.13.8")
}
