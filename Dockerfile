# =============================================================================
# Stage 1: Build Quarkus Native Executable using Mandrel JDK 21 (GraalVM AOT)
# =============================================================================
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS build
USER root
WORKDIR /work

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

COPY src src
# Compile directly to a standalone, zero-reflection native binary runner
RUN ./mvnw -B package -Dnative -DskipTests

# =============================================================================
# Stage 2: Ultra-minimal Runtime Container (Zero Java Runtime / JRE Dependency)
# =============================================================================
FROM debian:12-slim
LABEL maintainer="Jason <jason1.pan@hsbc.com.hk>"
LABEL org.opencontainers.image.source="https://github.com/nvd11/gemini-live-bridge"

WORKDIR /app

# Install root CA certificates and timezone data
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*

# Non-root user setup for high security
RUN groupadd -r bridge --gid 1000 && useradd -r -g bridge --uid 1000 -d /app bridge

# Copy the native executable runner
COPY --from=build --chown=bridge:bridge /work/target/*-runner /app/application

USER 1000
EXPOSE 8080

ENV QUARKUS_HTTP_HOST=0.0.0.0

ENTRYPOINT ["./application"]
