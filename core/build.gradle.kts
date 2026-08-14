import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        val localProperties = Properties()
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }
        val tmdbApi = System.getenv("TMDB_API") ?: localProperties.getProperty("TMDB_API", "")
        val tvdbApi = System.getenv("TVDB_API") ?: localProperties.getProperty("TVDB_API", "")
        val webhookUrl = System.getenv("WEBHOOK_URL") ?: localProperties.getProperty("WEBHOOK_URL", "")

        require(tmdbApi.isNotBlank()) { "TMDB_API missing" }
        require(tvdbApi.isNotBlank()) { "TVDB_API missing" }
        require(webhookUrl.isNotBlank()) { "WEBHOOK_URL missing" }

        buildConfigField("String", "TMDB_API", "\"$tmdbApi\"")
        buildConfigField("String", "TVDB_API", "\"$tvdbApi\"")
        buildConfigField("String", "WEBHOOK_URL", "\"$webhookUrl\"")
    }

    namespace = "keiyoushi.core"

    buildFeatures {
        resValues = false
        shaders = false
        buildConfig = true
    }
}

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(libs.bundles.common)
}
