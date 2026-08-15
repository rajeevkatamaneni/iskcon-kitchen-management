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
        fonts-indic \
        fonts-noto-ui-core \
        fontconfig \
        ca-certificates \
        curl \
        gnupg \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

# Non-root, per least-privilege in SYSTEM_DESIGN.md §7.
RUN groupadd --system --gid 1001 kms \
    && useradd --system --uid 1001 --gid kms kms

# ---------------------------------------------------------------------------
# Chromium, for the real PDF renderer.
#
# Every document in the product — recipe cards, purchase orders — is rendered by
# headless Chromium (TECH_STACK.md): correct Devanagari and Kannada shaping needs
# a real browser engine, and a pure-JVM PDF library cannot do it. Without a
# browser the application falls back to the stub renderer, which produces a
# four-line placeholder that no PDF reader will open.
#
# The version must match com.microsoft.playwright:playwright in build.gradle.kts —
# the Java client looks for the exact browser build its own driver pins, and a
# mismatch fails at launch with "Executable doesn't exist".
#
# Installed to a shared path and owned by the runtime user, because Cloud Run
# runs this container as a non-root user with no writable home.
# ---------------------------------------------------------------------------
# Playwright's installer is a Node program and needs Node 14 or newer. Jammy's own `nodejs`
# package is Node 12, which fails outright — hence NodeSource rather than apt's version.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npx --yes playwright@1.47.0 install --with-deps chromium \
    && chown -R kms:kms /ms-playwright \
    && apt-get purge -y --auto-remove curl gnupg \
    && rm -rf /var/lib/apt/lists/* /root/.npm

WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
RUN chown -R kms:kms /app

USER kms

# Cloud Run injects PORT; application.yml reads it with a local default of 8080.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
