plugins {
    id("java-library")
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.sunny.guardian"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Core Dependencies
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.jspecify:jspecify")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("io.micrometer:micrometer-core")
    // Test Dependencies (Unit Tests + Testcontainers)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.redis.testcontainers:testcontainers-redis-junit:1.6.4")
}

// CRITICAL: Disable bootJar so we produce a plain library JAR, not an executable
tasks.named("bootJar") {
    enabled = false
}

tasks.named("jar") {
    enabled = true
}

tasks.test {
    useJUnitPlatform()
}