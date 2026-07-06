# ── Stage 1: build ─────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

COPY src src
RUN ./mvnw clean package -DskipTests -B

RUN mkdir -p target/extracted && \
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ── Stage 2: runtime — minimal image, no build tooling ─────────────────
FROM eclipse-temurin:21-jre-jammy

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /workspace/target/extracted/application/ ./

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
