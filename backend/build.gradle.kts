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
	implementation("org.springframework.boot:spring-boot-starter-quartz")
	// Email goes out over authenticated SMTP relay rather than a paid API service. Cloud Run blocks
	// outbound port 25 permanently, so 587 to a relay we already have an account with is the whole
	// of the transport — no new provider, no new bill.
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("com.google.firebase:firebase-admin:9.4.1")

	// Observability (E1-S11): JSON logs, error tracking, Prometheus metrics.
	implementation("net.logstash.logback:logstash-logback-encoder:8.0")
	implementation("io.sentry:sentry-spring-boot-starter-jakarta:8.52.0")
	implementation("io.micrometer:micrometer-registry-prometheus")

	// Recipe documents (E2-S5): HTML->PDF via headless Chromium, and object storage in GCS.
	implementation("com.microsoft.playwright:playwright:1.47.0")
	implementation(platform("com.google.cloud:libraries-bom:26.48.0"))
	implementation("com.google.cloud:google-cloud-storage")
	// Recipe translation (E2-S6): Google Cloud Translation v3 (glossary-capable).
	implementation("com.google.cloud:google-cloud-translate")
	// A temple's own payment credentials (E7). They are never kept in our schema — see
	// TenantSecretStore — so the deployed store talks to Secret Manager. The in-memory default
	// keeps the suite hermetic.
	implementation("com.google.cloud:google-cloud-secretmanager")

	// Payments (E7): Razorpay is the first provider behind the PaymentGateway port. The SDK is only
	// used by the razorpay-selected adapter; the default stub keeps the test suite hermetic.
	implementation("com.razorpay:razorpay-java:1.4.8")

	// Newsletters a temple admin writes or pastes in (E8-S2). The paste is the reason: what arrives
	// from Word or Google Docs is a wall of vendor markup, and what a temple types is trusted only as
	// far as the person typing it. Sanitising HTML by hand is a well-known way to ship an XSS hole,
	// so this is the one place a purpose-built library is worth more than any code we would write.
	implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1")

	// Temple data export (E1-S15): the workbook an operator must take before erasing a temple.
	// Excel rather than CSV or JSON because the likely reader is a temple accountant, not a
	// developer — see the story's D7.
	implementation("org.apache.poi:poi-ooxml:5.3.0")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("org.awaitility:awaitility")
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
