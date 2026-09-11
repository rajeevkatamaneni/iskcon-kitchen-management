package org.iskcon.kms.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what a measurement run found and puts it somewhere a person will actually read.
 *
 * <p><strong>Written to a file as well as to standard output, and that is not belt-and-braces.</strong>
 * {@code build.gradle.kts} configures {@code testLogging} for PASSED/FAILED/SKIPPED and does not set
 * {@code showStandardStreams}, so a {@code System.out.println} from inside a test does not reach the
 * Gradle console at all. A performance harness whose numbers are invisible is a harness nobody runs
 * twice. The file is the answer, because this task may not edit the build script.
 *
 * <p>The path is printed at the end of every run, so the next person does not have to know it.
 */
final class PerfReport {

	private final List<String> lines = new ArrayList<>();
	private final Path destination;

	PerfReport(String fileName) {
		this.destination = Path.of("build", "reports", "perf", fileName);
	}

	PerfReport say(String line) {
		lines.add(line);
		System.out.println(line);
		return this;
	}

	PerfReport blank() {
		return say("");
	}

	PerfReport heading(String title) {
		blank();
		say("──────────────────────────────────────────────────────────────────────");
		say("  " + title);
		return say("──────────────────────────────────────────────────────────────────────");
	}

	Path write() {
		try {
			Files.createDirectories(destination.getParent());
			Files.writeString(destination, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the performance report", e);
		}
		System.out.println("Report written to " + destination.toAbsolutePath());
		return destination.toAbsolutePath();
	}

	// -------------------------------------------------------------------------------------------

	/**
	 * A set of wall-clock samples for one call.
	 *
	 * <p>Reported as fastest / median / slowest rather than as a mean, and every figure in
	 * milliseconds to one decimal place. A mean over seven samples on a laptop that is also running a
	 * PostgreSQL container is dominated by whichever sample happened to collide with something else;
	 * the median says what the call costs and the slowest says what a person occasionally waits.
	 *
	 * <p><strong>Nothing here asserts a threshold, on purpose.</strong> A millisecond count that
	 * fails a build flakes on a loaded machine, and a test that flakes teaches people to re-run
	 * rather than read. If a gate is ever wanted it should be a tracked number compared between two
	 * runs of this harness, not an absolute ceiling compiled into a test.
	 */
	record Timing(String label, List<Long> nanos) {

		double fastestMillis() {
			return sorted().get(0) / 1_000_000.0;
		}

		double medianMillis() {
			List<Long> s = sorted();
			return s.get(s.size() / 2) / 1_000_000.0;
		}

		double slowestMillis() {
			List<Long> s = sorted();
			return s.get(s.size() - 1) / 1_000_000.0;
		}

		private List<Long> sorted() {
			List<Long> copy = new ArrayList<>(nanos);
			copy.sort(Long::compare);
			return copy;
		}

		String describe() {
			return "%-46s  fastest %8.1f ms   median %8.1f ms   slowest %8.1f ms   (%d samples)"
					.formatted(label, fastestMillis(), medianMillis(), slowestMillis(), nanos.size());
		}
	}

	static String humanise(Duration duration) {
		long millis = duration.toMillis();
		if (millis < 1000) {
			return millis + " ms";
		}
		long seconds = millis / 1000;
		if (seconds < 60) {
			return "%d.%01d s".formatted(seconds, (millis % 1000) / 100);
		}
		return "%d m %02d s".formatted(seconds / 60, seconds % 60);
	}

	/** 146150 -> "146,150". Thousands separated, because a wall of digits is a wall of digits. */
	static String grouped(long value) {
		return String.format("%,d", value);
	}
}
