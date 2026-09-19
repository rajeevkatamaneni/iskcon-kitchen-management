package org.iskcon.kms.document;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How the backend writes a date for a person to read: on every generated document (PO sheet, recipe
 * card, donation receipt, work order, job card) and in the parameters of a WhatsApp message (T-312).
 * The one place those formats are stated, so a sheet and the screen it was printed from cannot drift
 * apart again.
 *
 * <p><b>Why this exists.</b> Each of those places carried its own {@code ofPattern("d MMM yyyy")}
 * with no locale, so it took the JVM's default, which is US English on the servers and on a Mac, and
 * wrote "20 Sep 2026" while every screen wrote "20 Sept 2026": the screens format with {@code en-GB}
 * ({@code frontend/lib/format.ts}). T-310 fixed the PO sheet's copy and T-311 the WhatsApp copy, each
 * privately, and the receipt, the work order and the job card still wrote "Sep". Five copies of one
 * rule is how four of them were missed; this is the one copy.
 *
 * <p><b>{@code Locale.UK}, named rather than left to the machine.</b> It gives the same twelve
 * abbreviations as the browser's {@code en-GB} (checked month by month on JDK 21 in T-310: Jan … Aug,
 * Sept, Oct … Dec; only September differs from US English), and it stops the documents changing if a
 * server's default locale ever does. The long formats are pinned for the second reason only: the
 * English day and month names are the same either way.
 *
 * <p><b>No zone on any of them, on purpose.</b> The zone belongs to whichever temple is printing, so
 * callers format {@code instant.atZone(clock.zone())}; a zone fixed here would be a static decision
 * about a fact that is per tenant.
 *
 * <p>{@code DateTimeFormatter} is immutable and thread-safe, so the formatters are shared as
 * constants. Public, and in {@code document}, for the reason {@link IndianNumbers} is: {@code donation}
 * and {@code purchaseorder} need it too, and neither is the other's natural home.
 * {@code DisplayDatesTest} checks that no other main source builds its own abbreviated-month
 * formatter, so a sixth copy fails the build instead of waiting for someone to notice "Sep".
 */
public final class DisplayDates {

	private DisplayDates() {
	}

	/** "20 Sept 2026": a day, the way the screens write one ({@code dateWithYear}, {@code templeDay}). */
	public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

	/**
	 * "20 Sept 2026, 14:30": a moment, the same day with the temple's clock on the end, as the screens'
	 * {@code moment} writes it. Seconds are left off, as everywhere else.
	 */
	public static final DateTimeFormatter DAY_AND_TIME =
			DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK);

	/** "Sunday 20 September 2026": the day a sheet is for, written out in full at its head. */
	public static final DateTimeFormatter LONG_DAY =
			DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK);
}
