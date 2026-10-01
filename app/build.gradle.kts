plugins {
    alias(libs.plugins.android.application)
    // AGP 9 applies Kotlin support itself; org.jetbrains.kotlin.android is rejected.
}

android {
    namespace = "dev.crewx.voiceassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.crewx.voiceassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Shrinking is deferred until a release build exists to measure;
            // enabling it blind here would just hide R8 config bugs.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // SQLCipher rather than framework SQLite: the resolver's FTS5 tier is the
    // whole point of the schema, and framework SQLite does not expose FTS5 on
    // every API level in minSdk 26.
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.sqlcipher.android)
}
