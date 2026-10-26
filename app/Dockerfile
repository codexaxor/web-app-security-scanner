# One image for both the api and worker processes; the active profile is chosen at run time
# via SPRING_PROFILES_ACTIVE (D-009).

# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core/build.gradle.kts core/gradle.lockfile ./core/
COPY app/build.gradle.kts app/gradle.lockfile ./app/
# Warm the dependency cache before copying sources (better layer caching). Lockfiles (copied above) make this
# deterministic; a genuine resolution failure must surface, so no `|| true` masks it (stdout — the verbose
# dependency tree — is suppressed, but stderr and a non-zero exit fail the build).
RUN ./gradlew --no-daemon :app:dependencies >/dev/null
COPY core ./core
COPY app ./app
RUN ./gradlew --no-daemon :app:bootJar -x test

# --- Run stage ---
FROM eclipse-temurin:21-jre AS run
# Non-root (CLAUDE.md security rules).
RUN useradd --system --uid 10001 --home /app secscan
WORKDIR /app
COPY --from=build /src/app/build/libs/*.jar app.jar
USER 10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
