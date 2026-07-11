FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -o clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/target/selfieproxy-portal.jar ./selfieproxy-portal.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "selfieproxy-portal.jar"]
