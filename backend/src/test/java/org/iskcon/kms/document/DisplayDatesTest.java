package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The backend's one way of writing a date for a person (T-312). The screens format with
 * {@code en-GB} ({@code frontend/lib/format.ts}); these pin the backend to the same output, and pin
 * that nothing else in the backend builds its own abbreviated-month formatter, because five private
 * copies is how "Sep" survived on the receipt, the work order and the job card after the PO sheet and
 * WhatsApp had been fixed.
 */
class DisplayDatesTest {

	@Test
	@DisplayName("a day is written the screens' way, twelve months of it: Sept, never the US Sep")
	void everyMonthMatchesEnGb() {
		// The browser's en-GB short months, as T-310 measured them against Node on 2026-09-19.
		List<String> enGb = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept",
				"Oct", "Nov", "Dec");
		for (int month = 1; month <= 12; month++) {
			assertThat(DisplayDates.DAY.format(LocalDate.of(2026, month, 20)))
					.isEqualTo("20 " + enGb.get(month - 1) + " 2026");
		}
	}

	@Test
	@DisplayName("a moment is the same day with the clock on the end; the long form spells it out")
	void momentAndLongDay() {
		assertThat(DisplayDates.DAY_AND_TIME.format(LocalDateTime.of(2026, 9, 20, 14, 5)))
				.isEqualTo("20 Sept 2026, 14:05");
		assertThat(DisplayDates.LONG_DAY.format(LocalDate.of(2026, 9, 20)))
				.isEqualTo("Sunday 20 September 2026");
	}

	/**
	 * The guard. Any {@code ofPattern("… MMM …")} outside {@code DisplayDates} is a second copy of the
	 * rule, and with no locale it writes "Sep". Full month names ({@code MMMM}) are left alone: they
	 * are the same in US and British English, and {@code LeaveService} spells one out on purpose.
	 */
	@Test
	@DisplayName("no main source but DisplayDates builds its own short-month date formatter")
	void noOtherShortMonthFormatter() throws IOException {
		Pattern shortMonth = Pattern.compile("ofPattern\\(\\s*\"([^\"]*(?<!M)MMM(?!M)[^\"]*)\"");
		List<String> offenders = new ArrayList<>();
		Path root = Path.of("src/main/java");
		assertThat(root).isDirectory();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				if (file.getFileName().toString().equals("DisplayDates.java")) {
					continue;
				}
				Matcher m = shortMonth.matcher(Files.readString(file));
				while (m.find()) {
					offenders.add(root.relativize(file) + ": " + m.group(1));
				}
			}
		}
		assertThat(offenders)
				.as("use DisplayDates.DAY or DAY_AND_TIME instead of a private formatter")
				.isEmpty();
	}
}
