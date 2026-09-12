package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one statement of what a shift's date and two times mean (T-146).
 *
 * <p>A plain unit test and not an integration one, deliberately: this is arithmetic with no database
 * in it, and the same rule is proved against a real PostgreSQL — including the {@code
 * shift_ends_at()} function that mirrors it — by {@code ShiftIT} and {@code VolunteerSignupIT}. What
 * is worth pinning here is the boundary behaviour nobody looks at twice, because every one of these
 * cases is a sentence somebody could reasonably have written the other way round.
 */
class ShiftWindowTest {

	private static final LocalDate DAY = LocalDate.of(2026, 9, 4); // Janmashtami

	@Test
	@DisplayName("a shift ending later the same day does not cross midnight")
	void ordinaryShift() {
		assertThat(ShiftWindow.crossesMidnight(LocalTime.of(8, 0), LocalTime.of(12, 0))).isFalse();
		assertThat(ShiftWindow.endDate(DAY, LocalTime.of(8, 0), LocalTime.of(12, 0))).isEqualTo(DAY);
		assertThat(ShiftWindow.endsAt(DAY, LocalTime.of(8, 0), LocalTime.of(12, 0)))
				.isEqualTo(DAY.atTime(12, 0));
		assertThat(ShiftWindow.length(LocalTime.of(8, 0), LocalTime.of(12, 0)))
				.isEqualTo(Duration.ofHours(4));
	}

	@Test
	@DisplayName("20:00 to 02:00 is six hours long and ends the next day — the shift this task exists for")
	void theMidnightOffering() {
		LocalTime start = LocalTime.of(20, 0);
		LocalTime end = LocalTime.of(2, 0);

		assertThat(ShiftWindow.crossesMidnight(start, end)).isTrue();
		assertThat(ShiftWindow.endDate(DAY, start, end)).isEqualTo(DAY.plusDays(1));
		assertThat(ShiftWindow.startsAt(DAY, start)).isEqualTo(DAY.atTime(20, 0));
		assertThat(ShiftWindow.endsAt(DAY, start, end)).isEqualTo(DAY.plusDays(1).atTime(2, 0));
		// The figure that goes negative if anybody ever subtracts the two times directly. Nothing
		// computes hours contributed yet; this is the sum that should be reached for when something
		// does.
		assertThat(ShiftWindow.length(start, end)).isEqualTo(Duration.ofHours(6));
		assertThat(ShiftWindow.length(start, end)).isPositive();
	}

	@Test
	@DisplayName("a minute either side of midnight is placed on the right day")
	void theMinuteEitherSide() {
		// Ends at 23:59 the same evening: still an ordinary shift, however late.
		assertThat(ShiftWindow.endsAt(DAY, LocalTime.of(20, 0), LocalTime.of(23, 59)))
				.isEqualTo(DAY.atTime(23, 59));
		// Ends one minute past midnight: the next day, by four hours and one minute of shift.
		assertThat(ShiftWindow.endsAt(DAY, LocalTime.of(20, 0), LocalTime.MIDNIGHT))
				.isEqualTo(DAY.plusDays(1).atStartOfDay());
		assertThat(ShiftWindow.length(LocalTime.of(20, 0), LocalTime.MIDNIGHT))
				.isEqualTo(Duration.ofHours(4));
		// And a shift that starts at midnight is an ordinary early-morning one, not an overnight
		// one: 00:00–04:00 ends the same day. The comparison is on the end, never on the start.
		assertThat(ShiftWindow.crossesMidnight(LocalTime.MIDNIGHT, LocalTime.of(4, 0))).isFalse();
		assertThat(ShiftWindow.endDate(DAY, LocalTime.MIDNIGHT, LocalTime.of(4, 0))).isEqualTo(DAY);
	}

	@Test
	@DisplayName("the start instant is the date and the start time, whatever the end does")
	void theStartIsUntouched() {
		// Said out loud because it is the half of T-146 that needed no change and could easily have
		// been "fixed" into something wrong. Every guard in SignupService is anchored here.
		assertThat(ShiftWindow.startsAt(DAY, LocalTime.of(20, 0)))
				.isEqualTo(ShiftWindow.startsAt(DAY, LocalTime.of(20, 0)))
				.isEqualTo(DAY.atTime(20, 0));
	}

	@Test
	@DisplayName("a person reads the hours, and is told when they run into the next morning")
	void whatAPersonReads() {
		assertThat(ShiftWindow.describe(LocalTime.of(8, 0), LocalTime.of(12, 0))).isEqualTo("08:00–12:00");
		assertThat(ShiftWindow.describe(LocalTime.of(20, 0), LocalTime.of(2, 0)))
				.isEqualTo("20:00–02:00 (next day)");
		// No seconds, whatever the column carries (INT-8).
		assertThat(ShiftWindow.describe(LocalTime.of(6, 30, 45), LocalTime.of(10, 0)))
				.isEqualTo("06:30–10:00");
	}
}
