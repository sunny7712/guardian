plugins {
    id("java")
    id("application")
    id("org.springframework.boot") version "4.0.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.sunny.guardian"
version = "1.0.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

// Disable bootJar and jar for the root project since it has no source
tasks.named("bootJar") {
    enabled = false
}

tasks.named("jar") {
    enabled = false
}

subprojects {
    pluginManager.withPlugin("java") {
        dependencies {
            "compileOnly"("org.projectlombok:lombok:1.18.30")
            "annotationProcessor"("org.projectlombok:lombok:1.18.30")
            "testImplementation"("org.junit.jupiter:junit-jupiter")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}