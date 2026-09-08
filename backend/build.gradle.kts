import java.util.concurrent.atomic.AtomicBoolean
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

	// ---------------------------------------------------------------------------
	// The test JVM's heap, stated rather than inherited.
	//
	// Gradle hands a test worker 512 MB when the build says nothing, and this build said
	// nothing. That default is sized for unit tests. This is not a unit-test suite, and the
	// measurements below are from a full run of it on 2026-09-08 (1830 tests).
	//
	// It creates about a hundred distinct Spring application contexts. Nearly every
	// integration class declares its own nested `StubVerifierConfiguration` and `@Import`s
	// it, and an imported configuration class forms part of the TestContext framework's
	// cache key — so each such class asks for a context of its own. A full run opens 106
	// Hikari pools, one per context.
	//
	// Spring caches 32 of those and evicts the rest, but evicting is not releasing. A class
	// histogram taken from the live worker (jcmd GC.class_histogram, which compacts first,
	// so these are survivors) three quarters of the way through a run found *81* live
	// `AnnotationConfigServletWebServerApplicationContext`, and 81 each of HikariDataSource,
	// HikariPool, SessionFactoryImpl and TomcatWebServer beside them. Only 32 were still
	// running. The other 49 were closed and still reachable. So what the run retains grows
	// with the number of contexts it has created, not with the size of the cache — which is
	// exactly why the failures always landed on the last classes to run.
	//
	// That histogram totalled 886 MB live. Squeezed into a smaller heap the same suite
	// compacts to about 446 MB, because much of the rest is soft-referenced cache — AspectJ
	// shadow matches and reflection metadata, over a million instances each — that the
	// collector discards under pressure. Both numbers matter: 446 MB is the floor the suite
	// cannot go below, and 886 MB is what it wants.
	//
	// Gradle's 512 MB default sat directly on that floor. The suite passed only by running
	// permanently in emergency collection, and any variation at all tipped it over: the
	// worker died with OutOfMemoryError while building the next context, JUnit reported it
	// as `Failed to load ApplicationContext` against whichever classes ran last, not one
	// assertion failed, and a re-run of the identical commit was green. Commit 22e820a,
	// which changed one markdown file and nothing else, failed exactly that way. Held at
	// 448 MB — 64 MB below the old ceiling — it reproduces on demand: 23 OutOfMemoryErrors,
	// the worker dead after 1260 of 1830 tests, a summary reading `Failed: 0` beside
	// `Result: FAILURE`, and 8m10s instead of 3m17s, most of it spent collecting.
	//
	// 2 GB is roughly twice the measured live set and four times the floor, on a runner with
	// 16 GB whose only other tenants are the Gradle process and one Postgres container. It
	// is a ceiling, not a reservation. It is deliberately not larger: a much bigger heap
	// would hide the retention rather than pay for it, and the retention is the actual
	// defect. This buys room; it does not repair anything.
	//
	// The repair is two changes to the test sources, neither of them in this file: give
	// those classes one shared stub-verifier configuration instead of the 88 private ones
	// they declare today, which collapses about a hundred cached contexts into a handful;
	// and find what holds a closed context reachable. Both are filed separately.
	// ---------------------------------------------------------------------------
	maxHeapSize = "2g"

	// Printed on every run, passing or failing. Its absence is why the condition above cost
	// three investigations across two releases: the log said a context had failed to load,
	// and said nothing whatever about how much heap it had been given to load it into.
	val heapCeiling = maxHeapSize
	doFirst {
		logger.lifecycle("Test JVM heap ceiling: $heapCeiling (Gradle's default, when unset, is 512m)")
	}

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

	// Per-class and overall totals — and a name for the one failure this suite has that is
	// not a failure of the code.
	//
	// When the worker exhausts its heap there are two ways it surfaces, and neither says so.
	// If a context load is what runs out, JUnit reports a crowd of `Failed to load
	// ApplicationContext` errors and the OutOfMemoryError is buried as their root cause. If
	// the JVM dies outright, Gradle reports that the worker could not complete and the
	// summary reads `Failed: 0` beside `Result: FAILURE`. Both read like a broken commit;
	// neither is one. Saying so here is the whole point — a suite that reddens for a reason
	// unrelated to the change under test teaches the next reader to re-run rather than read,
	// and the genuine failure after that gets the same glance and the same dismissal.
	val heapExhausted = AtomicBoolean(false)

	fun rootedInHeapExhaustion(failure: Throwable): Boolean =
		// The failure is serialised out of the worker, so by the time it arrives the
		// OutOfMemoryError may be a placeholder that kept only the original class name.
		// Hence matching the rendered text as well as the type.
		generateSequence(failure) { it.cause }
			.take(20)
			.any { it is OutOfMemoryError || it.toString().contains("java.lang.OutOfMemoryError") }

	addTestListener(object : TestListener {
		override fun beforeSuite(suite: TestDescriptor) {}
		override fun beforeTest(test: TestDescriptor) {}
		override fun afterTest(test: TestDescriptor, result: TestResult) {
			if (result.exceptions.any(::rootedInHeapExhaustion)) {
				heapExhausted.set(true)
			}
		}

		override fun afterSuite(suite: TestDescriptor, result: TestResult) {
			if (result.exceptions.any(::rootedInHeapExhaustion)) {
				heapExhausted.set(true)
			}

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

				// A build that failed while every test that ran passed is the second face of the
				// same thing: the worker died mid-suite, and by far its commonest reason is heap.
				// Worded as a likelihood rather than a diagnosis, because a worker can die for
				// other reasons and this banner must not tell a comfortable lie about a real one.
				val diedWithNothingFailing =
					result.resultType == TestResult.ResultType.FAILURE && result.failedTestCount == 0L

				if (heapExhausted.get() || diedWithNothingFailing) {
					val headline =
						if (heapExhausted.get()) "THE TEST JVM RAN OUT OF HEAP. THIS IS NOT A CODE FAILURE."
						else "THE BUILD FAILED, BUT NOT ONE TEST DID. READ THIS BEFORE RE-RUNNING."
					logger.lifecycle(
						"""
						────────────────────────────────────────────────────────
						  $headline
						────────────────────────────────────────────────────────
						  The test worker ran out of memory, or died. Any failure
						  above reading "Failed to load ApplicationContext" is that
						  one OutOfMemoryError seen from JUnit's side; no assertion
						  is broken. Re-running will very likely be green, and that
						  reflex is exactly what this message exists to stop.
						
						  This suite creates about a hundred Spring application
						  contexts and keeps 81 of them reachable at once. It needs
						  446 MB of heap at the very least and wants 886 MB. It was
						  given $heapCeiling. Raise maxHeapSize in backend/build.gradle.kts,
						  or cut the number of distinct test contexts. Do not simply
						  run it again.
						────────────────────────────────────────────────────────
						""".trimIndent())
				}
			}
		}
	})
}

// ---------------------------------------------------------------------------
// A task whose only job is to pull every dependency down.
//
// The Dockerfile used to run `gradle dependencies || true` above the source copy
// and call that a cache layer. It was not one: `dependencies` prints the graph,
// which needs the metadata but not the jars, and `|| true` hid it when even that
// failed. So every image build re-downloaded the whole tree while compiling.
//
// Resolving each resolvable configuration downloads the artifacts themselves, so
// the layer above `COPY src` genuinely holds them and a source-only change reuses
// it. Nothing else calls this; it exists for the Dockerfile.
// ---------------------------------------------------------------------------
tasks.register("resolveDependencies") {
	description = "Downloads every resolvable dependency, so a Docker layer can cache them."
	doLast {
		configurations.filter { it.isCanBeResolved }.forEach { configuration ->
			runCatching { configuration.resolve() }
				.onFailure { logger.lifecycle("Skipped ${configuration.name}: ${it.message}") }
		}
	}
}
