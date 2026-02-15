# Multi-stage build for Anypoint MQ Prometheus Exporter
FROM eclipse-temurin:17-jdk-alpine AS builder

# Set working directory
WORKDIR /app

# Copy Maven files first for better caching
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN ./mvnw dependency:resolve

# Copy source code
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

# Add labels for better container management
LABEL org.opencontainers.image.title="Anypoint MQ Prometheus Exporter"
LABEL org.opencontainers.image.description="Prometheus exporter for MuleSoft Anypoint MQ metrics"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.authors="Netflexity"
LABEL org.opencontainers.image.source="https://github.com/netflexity/anypoint-mq-prometheus-exporter"

# Install curl for health checks
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -g 1001 exporter && \
    adduser -D -s /bin/sh -u 1001 -G exporter exporter

# Set working directory
WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder /app/target/anypoint-mq-prometheus-exporter-*.jar app.jar

# Change ownership of the app directory
RUN chown -R exporter:exporter /app

# Switch to non-root user
USER exporter

# Expose the application port
EXPOSE 9101

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:9101/actuator/health || exit 1

# Set JVM options for containerized environments
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]