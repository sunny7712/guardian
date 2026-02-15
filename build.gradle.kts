plugins {
    id("java")
    id("application")
    id("org.springframework.boot") version "3.4.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

group = "com.sunny.guardian"
version = "1.0.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
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