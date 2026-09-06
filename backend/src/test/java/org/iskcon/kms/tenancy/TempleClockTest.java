package org.iskcon.kms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One clock, and no copies of it.
 *
 * <p>Rajeev, 2026-09-05: <em>"ALL Date and Time values for that Temple MUST be in that Time zone
 * irrespective of where the Temples dedicated tenant is being accessed from."</em> Twenty-one
 * services each held their own {@code ZoneId.of("Asia/Kolkata")}, which was right for every temple
 * onboarded so far and would have been wrong in twenty-one places at once for the first one that is
 * not. They read {@link TempleClock} now.
 *
 * <p>This is a source test rather than a behavioural one because the failure it guards against is
 * not a wrong answer today — it is the twenty-second copy, added next month by somebody who needed a
 * zone and reached for the literal that was in every file around them.
 */
class TempleClockTest {

	private static final Path SOURCE = Path.of("src/main/java/org/iskcon/kms");

	/**
	 * Where a fixed zone is still the honest answer.
	 *
	 * <p>{@code OpsService} is the platform operator's cross-tenant dashboard: somebody comparing two
	 * temples wants one clock, not each temple's. {@code JobSchedulingConfiguration} holds Quartz
	 * crons that fire once for the whole platform, and per-tenant scheduling is a feature rather than
	 * a find-and-replace. Both say so where they stand.
	 */
	private static final List<String> ALLOWED = List.of(
			"tenancy/TempleClock.java",
			"ops/OpsService.java",
			"jobs/JobSchedulingConfiguration.java");

	@Test
	@DisplayName("no service keeps its own copy of the temple's time zone")
	void oneClock() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String relative = SOURCE.relativize(file).toString();
				if (ALLOWED.stream().anyMatch(relative::endsWith)) {
					continue;
				}
				for (String line : Files.readAllLines(file)) {
					// A comment may name the zone — several explain why they no longer hold one.
					String code = line.strip();
					if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
						continue;
					}
					if (code.contains("Asia/Kolkata")) {
						offenders.add(relative + ": " + code);
					}
				}
			}
		}
		assertThat(offenders)
				.as("these should ask TempleClock which zone the temple keeps")
				.isEmpty();
	}
}
