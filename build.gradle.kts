plugins {
    id("java")
    id("application")
    id("com.google.protobuf") version "0.9.5"
}

group = "com.sunny.guardian"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.grpc:grpc-stub:1.77.0")
    implementation("io.grpc:grpc-protobuf:1.77.0")
    implementation("io.grpc:grpc-netty-shaded:1.77.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {artifact = "com.google.protobuf:protoc:3.24.0"}
    plugins {

        // Registers a protoc plugin named grpc and points it at the artifact
        // this plugin generates gRPC service/stub code
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.77.0"
        }
    }

    // for every proto generation task, attach the grpc plugin
    generateProtoTasks {
        all().forEach { task -> task.plugins.create("grpc") }
    }
}

tasks.test {
    useJUnitPlatform()
}