# Multi-stage production image.
# Build: docker build -t ledgerflow:latest .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
# Run as a dedicated non-root user.
RUN useradd --system --uid 10001 ledgerflow
USER 10001
WORKDIR /app
COPY --from=build /workspace/target/ledgerflow-*.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=json-logs
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
