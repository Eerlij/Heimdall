# syntax=docker/dockerfile:1

# --- Build: compile and extract Spring Boot layers -----------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Optional extra CA for machines behind TLS-intercepting proxies/antivirus (local builds only; unused in CI).
RUN --mount=type=secret,id=extra-ca-cert,required=false \
    if [ -s /run/secrets/extra-ca-cert ]; then \
      keytool -importcert -noprompt -cacerts -storepass changeit -alias extra-ca -file /run/secrets/extra-ca-cert; \
    fi
# Resolve dependencies first so source changes don't invalidate this layer.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests -Dspotless.check.skip=true \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# --- Runtime: JRE only, non-root ------------------------------------------------
FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin heimdall
WORKDIR /app
# Least to most frequently changing, for layer caching.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER heimdall
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
