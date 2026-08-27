plugins {
    kotlin("jvm") version "2.4.0"
}

group = "com.hartmann"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("io.rest-assured:rest-assured:5.5.1")
    // testcontainers/rest-assured pull in slf4j-api transitively for their own internal logging;
    // without a binding SLF4J prints a one-time "no binding found, defaulting to NOP" warning
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.18")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    testImplementation("org.postgresql:postgresql:42.7.13")
}

kotlin {
    jvmToolchain(26)
}

tasks.test {
    useJUnitPlatform()
    // e2e tests spin up the full docker-compose stack and hit real Stripe test-mode APIs -
    // they are slow and belong to their own CI stage, not the regular unit/integration test run.
    systemProperty("junit.jupiter.execution.timeout.default", "5m")
}
