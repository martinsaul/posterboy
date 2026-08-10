plugins {
    kotlin("jvm") version "2.1.10"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.posterboy"
version = "0.1.0"

application {
    mainClass.set("io.posterboy.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // SMTP server
    implementation("org.subethamail:subethasmtp:3.1.7")

    // Email parsing
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // YAML config
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    // HTTP client (Discord webhooks)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // DNS lookups
    implementation("dnsjava:dnsjava:3.6.2")

    // Admin UI web server
    implementation("io.javalin:javalin:6.4.0")

    // SQLite (approval queue)
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // Gmail API
    implementation("com.google.apis:google-api-services-gmail:v1-rev20260112-2.0.0")
    implementation("com.google.api-client:google-api-client:2.9.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.43.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // Exclude dnsjava's InetAddressResolverProvider SPI -- it hijacks JVM DNS
    // resolution and breaks in fat jars. We use dnsjava directly via its Lookup API.
    exclude("META-INF/services/java.net.spi.InetAddressResolverProvider")
}
