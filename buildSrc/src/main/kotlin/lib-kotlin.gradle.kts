plugins {
    `java-library`
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

versionCatalogs
    .named("libs")
    .findLibrary("kotlin-stdlib")
    .ifPresent { stdlib ->
        dependencies {
            compileOnly(stdlib)
        }
    }
