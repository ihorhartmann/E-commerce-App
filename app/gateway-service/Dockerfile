#1 compiling
FROM gradle:9.6.1-jdk26-alpine AS gradle_builder

WORKDIR /gateway_service
COPY build.gradle.kts settings.gradle.kts gradle/ ./
RUN gradle dependencies --no-daemon

COPY src ./src
RUN gradle clean bootJar -x test --no-daemon

#2 copying & running
FROM eclipse-temurin:26.0.1_8-jre-alpine-3.23

WORKDIR /app
COPY --from=gradle_builder /gateway_service/build/libs/*.jar /app/gateway-service.jar

CMD ["java", "-jar", "/app/gateway-service.jar"]
