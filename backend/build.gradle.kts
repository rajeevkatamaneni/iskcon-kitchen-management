import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
	java
	id("org.springframework.boot") version "3.3.4"
	id("io.spring.dependency-management") version "1.1.6"
}

group = "org.iskcon"
version = "0.1.0-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Kept current deliberately. Testcontainers drives a real Docker daemon through docker-java,
// which negotiates the Docker Engine API version at runtime — but only recent versions negotiate
// against very new engines. 1.20.1 defaulted to API 1.32 and was refused by Docker Engine 29
// (minimum 1.40) on a developer machine, while CI's older runner Docker still accepted it: a
// green CI and a red laptop for the same commit. Staying on a current 1.x keeps the suite working
// across the Docker versions actually in play (see docs/CHANGELOG.md, 2026-08-09).
extra["testcontainersVersion"] = "1.21.4"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("com.google.firebase:firebase-admin:9.4.1")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()

	// Gradle prints nothing about passing tests by default, which makes a green build
	// unreviewable — you cannot tell what ran, or whether the thing you cared about was
	// even executed. Everything below exists so the log answers "what was verified?".
	testLogging {
		events(
			TestLogEvent.PASSED,
			TestLogEvent.FAILED,
			TestLogEvent.SKIPPED,
		)

		// @DisplayName rather than the method name, so each line reads as the behaviour
		// being asserted.
		displayGranularity = 2
		showExceptions = true
		showCauses = true
		showStackTraces = true

		// Full assertion output on failure, including AssertJ's .as(...) descriptions —
		// the truncated default hides which assertion actually broke.
		exceptionFormat = TestExceptionFormat.FULL
	}

	// Per-class and overall totals.
	addTestListener(object : TestListener {
		override fun beforeSuite(suite: TestDescriptor) {}
		override fun beforeTest(test: TestDescriptor) {}
		override fun afterTest(test: TestDescriptor, result: TestResult) {}

		override fun afterSuite(suite: TestDescriptor, result: TestResult) {
			if (suite.parent == null) {
				val summary = """

					────────────────────────────────────────────────────────
					  Test summary
					────────────────────────────────────────────────────────
					  Total:    ${result.testCount}
					  Passed:   ${result.successfulTestCount}
					  Failed:   ${result.failedTestCount}
					  Skipped:  ${result.skippedTestCount}
					  Result:   ${result.resultType}
					────────────────────────────────────────────────────────
				""".trimIndent()
				logger.lifecycle(summary)
			}
		}
	})
}
