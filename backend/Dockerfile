# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM gradle:8.10-jdk21 AS build
WORKDIR /src

# Dependency layer first so source changes don't re-download the world.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---------------------------------------------------------------------------
# Runtime stage
#
# Fonts are installed here, not later: TECH_STACK.md §2 records Indic script
# rendering as the hardest technical risk in the translation feature, and the
# fix is having the right fonts present in the image that runs Chromium.
# Installing them now means E2-S5's "Devanagari renders correctly" acceptance
# criterion is testable the day that story starts, not blocked on a rebuild.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
        fonts-noto-core \
        fonts-noto-devanagari \
        fonts-noto-ui-core \
        fontconfig \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

# Non-root, per least-privilege in SYSTEM_DESIGN.md §7.
RUN groupadd --system --gid 1001 kms \
    && useradd --system --uid 1001 --gid kms kms

WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
RUN chown -R kms:kms /app

USER kms

# Cloud Run injects PORT; application.yml reads it with a local default of 8080.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
