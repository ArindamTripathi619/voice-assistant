plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM on purpose: no Android dependency, so the resolver, normalizer,
// metaphone and fast-path grammar are all unit-testable without a device.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    // Test-only: lets the real schema, FTS5 triggers and every query in Sql.kt be
    // verified on the JVM. Android uses SQLCipher instead; the SQL is identical.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
