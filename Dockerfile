# --- Build Stage ---
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradle gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY guardian-core/build.gradle.kts guardian-core/
COPY guardian-test-server/build.gradle.kts guardian-test-server/

COPY guardian-core/src guardian-core/src
COPY guardian-test-server/src guardian-test-server/src

RUN ./gradlew :guardian-test-server:bootJar -x test --no-daemon

# --- Run Stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/guardian-test-server/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]