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
}
