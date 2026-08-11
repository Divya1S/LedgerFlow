# Multi-stage production image (dashboard included).
# Build: docker build -t ledgerflow:latest .
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY web/ .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
COPY --from=web /web/dist ./src/main/resources/static
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
