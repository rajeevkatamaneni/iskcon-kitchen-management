package org.iskcon.kms.shift;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * What a shift's two times and one date actually mean (T-146).
 *
 * <p><strong>{@code shift_date} is the date the shift STARTS.</strong> Everything else follows from
 * that one sentence, and it is written here so that no reader has to decide it for itself:
 *
 * <ul>
 * <li>A shift <em>crosses midnight</em> when {@code endTime} is at or before {@code startTime}.</li>
 * <li>Its end falls on {@code shiftDate + 1} when it does, and on {@code shiftDate} when it does
 *     not.</li>
 * <li>Its start is always {@code (shiftDate, startTime)}, whatever the end does.</li>
 * </ul>
 *
 * <p>Until T-146 an overnight shift could not be posted at all: {@code V34}'s
 * {@code CHECK (end_time > start_time)} refused 20:00–02:00, and the refusal surfaced as
 * {@code KMS-500001} — "something went wrong at our end, try again in a moment" — to a temple whose
 * largest festival is at midnight. {@code V127} changes the constraint to
 * {@code end_time <> start_time}, and this class is what stops that change turning into three
 * disagreeing implementations of the same arithmetic.
 *
 * <p><strong>One rule, three languages, each naming the others.</strong> This project's most
 * expensive recurring defect is the same sum written out by hand in several places, one of which
 * quietly drifts — {@code V74} exists because of exactly that and says so at length. So the rule
 * lives in one place per language and nowhere else:
 *
 * <ul>
 * <li>SQL — {@code shift_ends_at(DATE, TIME, TIME)}, created by {@code V127}. Used where a query has
 *     to compute the end instant of a row it is comparing against rather than one it is holding.</li>
 * <li>Java — this class.</li>
 * <li>TypeScript — {@code crossesMidnight()} and {@code shiftWindow()} in
 *     {@code frontend/lib/format.ts}, which say it on the screen.</li>
 * </ul>
 *
 * <p>A fourth implementation is not needed anywhere and should not be written. If a new reader needs
 * the end of a shift, it calls {@link #endsAt}; if it needs to say so to a person, it calls
 * {@link #describe}.
 *
 * <p>Static and final, with no instance to hold, because there is no state here — only the one
 * question every reader of a shift has to answer the same way.
 */
public final class ShiftWindow {

	/** "08:00", never "08:00:00". Seconds are noise on a screen and in a message (INT-8). */
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private ShiftWindow() {
	}

	/**
	 * Whether this shift runs through midnight and therefore ends on the day after its own date.
	 *
	 * <p>At or before, not strictly before. Equality is included for completeness rather than
	 * because it happens: {@code end_time = start_time} is refused by {@code shifts_time_window}
	 * (V127) and by Bean Validation before that, because it cannot be told apart from a
	 * twenty-four-hour shift. Were it ever to reach here, answering "crosses midnight" is the
	 * reading that at least keeps this class self-consistent with the SQL function, which uses the
	 * same {@code <=}.
	 */
	public static boolean crossesMidnight(LocalTime startTime, LocalTime endTime) {
		return !endTime.isAfter(startTime);
	}

	/** The date the shift ends on — the day after {@code shiftDate} when it crosses midnight. */
	public static LocalDate endDate(LocalDate shiftDate, LocalTime startTime, LocalTime endTime) {
		return crossesMidnight(startTime, endTime) ? shiftDate.plusDays(1) : shiftDate;
	}

	/**
	 * The moment the shift begins, on the temple's own wall clock.
	 *
	 * <p>Unchanged by T-146 and here anyway, so that a reader looking for one end of a shift finds
	 * both in the same place rather than finding this one spelled out inline at five call sites and
	 * assuming the other works the same way. The attendance gate, both release guards and the
	 * reminder scheduler all build this and are correct as written.
	 */
	public static LocalDateTime startsAt(LocalDate shiftDate, LocalTime startTime) {
		return LocalDateTime.of(shiftDate, startTime);
	}

	/** The moment the shift ends, on the temple's own wall clock. Mirrors {@code shift_ends_at()}. */
	public static LocalDateTime endsAt(LocalDate shiftDate, LocalTime startTime, LocalTime endTime) {
		return LocalDateTime.of(endDate(shiftDate, startTime, endTime), endTime);
	}

	/**
	 * How long the shift lasts — six hours for 20:00–02:00, and never a negative number.
	 *
	 * <p>The reason this exists before anything reads it: {@code end - start} on the two times alone
	 * goes <em>negative</em> across midnight, and the figures that would land on are the hours a
	 * devotee has contributed and their reliability. Neither is computed anywhere yet (confirmed
	 * across the tree for T-146 — the phrases appear only in comments describing what attendance is
	 * for), so there is nothing wrong today. This is the correct sum, sitting where whoever builds
	 * those figures will look for it, rather than an invitation to write the wrong one from scratch.
	 */
	public static Duration length(LocalTime startTime, LocalTime endTime) {
		return Duration.between(
				startsAt(LocalDate.EPOCH, startTime), endsAt(LocalDate.EPOCH, startTime, endTime));
	}

	/**
	 * The shift's hours as a person reads them — {@code "08:00–12:00"}, or
	 * {@code "20:00–02:00 (next day)"} where it runs through the night.
	 *
	 * <p>The parenthesis is the whole point. "20:00–02:00" read cold is a shift that ends sixteen
	 * hours before it begins, and a volunteer deciding whether they can make it should not have to
	 * work out which of the two readings the temple meant. It is the same sentence the screens print
	 * ({@code shiftWindow()} in {@code frontend/lib/format.ts}), so a reminder on WhatsApp and the
	 * roster it came from say the shift's hours the same way.
	 *
	 * <p>An en dash, matching every other range in this application, and no seconds (INT-8): a shift
	 * runs 08:00–12:00, not 08:00:00–12:00:00.
	 */
	public static String describe(LocalTime startTime, LocalTime endTime) {
		String window = CLOCK.format(startTime) + "–" + CLOCK.format(endTime);
		return crossesMidnight(startTime, endTime) ? window + " (next day)" : window;
	}
}
