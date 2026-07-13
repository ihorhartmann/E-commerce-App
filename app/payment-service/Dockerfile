#1 compiling
FROM maven:3.9.16-eclipse-temurin-26-alpine AS maven_builder

WORKDIR /payment_service
COPY pom.xml ./
RUN mvn dependency:go-offline -B
COPY . ./
RUN mvn clean package -DskipTests

#2 copying & running
FROM eclipse-temurin:26.0.1_8-jre-alpine-3.23

WORKDIR /app
COPY --from=maven_builder /payment_service/target/*.jar /app/payment-service.jar

CMD ["java","-jar","/app/payment-service.jar"]
