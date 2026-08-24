plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hartmann"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-kafka")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("org.awaitility:awaitility:4.3.0")
}

kotlin {
    jvmToolchain(26)
}

tasks.test {
    useJUnitPlatform {
        if (project.hasProperty("integration")) {
            includeTags("integration")
        } else {
            excludeTags("integration")
        }
    }
}
