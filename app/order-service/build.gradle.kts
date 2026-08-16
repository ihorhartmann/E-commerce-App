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
    implementation("tools.jackson.module:jackson-module-kotlin")
//    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
}

kotlin {
    jvmToolchain(26)
}

tasks.test {
    useJUnitPlatform()
}
