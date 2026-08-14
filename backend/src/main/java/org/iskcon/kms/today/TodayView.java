package org.iskcon.kms.today;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The temple's morning screen, in one payload (E4-S8).
 *
 * <p>Assembled from what the rest of the product already knows — meals, stock, shifts, giving,
 * deliveries — because this screen is the first thing loaded each morning, often on a phone on a
 * temple's connection. Six requests to draw one screen is a slow start to the day.
 *
 * <p>Nullable fields are the honest way to say "not for you": kitchen staff hold neither
 * {@code VIEW_DONATIONS} nor {@code MANAGE_VENDOR_PAYMENTS}, so {@link #giving()} and the money side
 * of {@link #deliveries()} are absent for them rather than zeroed. A zero would read as "nobody gave
 * anything this month", which is a different and wrong statement.
 *
 * @param date     the temple's today, in its own timezone.
 * @param calendar what the calendar says about today and tomorrow, or null where a temple has no
 *                 calendar computed yet.
 * @param giving   month-to-date giving, or null when the reader may not see donations.
 */
public record TodayView(
		LocalDate date,
		CalendarNote calendar,
		List<PlannedMeal> meals,
		int platesToday,
		int itemsBelowThreshold,
		int unfilledShiftSpots,
		String nextUnfilledShift,
		Giving giving,
		List<Delivery> deliveries) {

	/**
	 * What today and tomorrow ask of the kitchen. Tomorrow matters as much as today: a fast changes
	 * every menu on it, and menus are settled the day before.
	 *
	 * @param fastingToday    today excludes grains and beans.
	 * @param fastingTomorrow the same, for tomorrow — the banner case.
	 */
	public record CalendarNote(
			boolean fastingToday,
			boolean fastingTomorrow,
			String todayName,
			String tomorrowName,
			LocalTime sunrise) {
	}

	/**
	 * A meal of today, in the order the kitchen works: by the time its food must be ready.
	 *
	 * @param status PLANNED, COOKED or CANCELLED — what the kitchen has actually done with it.
	 */
	public record PlannedMeal(
			UUID id,
			String mealKind,
			LocalTime readyBy,
			String recipeName,
			BigDecimal targetServings,
			String status,
			String occasionName) {
	}

	/** Month-to-date giving, totalled across categories. */
	public record Giving(BigDecimal monthToDate, LocalDate since) {
	}

	/**
	 * Something expected from a vendor.
	 *
	 * @param state AWAITED for a sent order due today or earlier, INVOICE_OVERDUE for an invoice
	 *              past its due date. Both are the store keeper's first question of the morning.
	 */
	public record Delivery(
			UUID purchaseOrderId,
			String poNumber,
			String vendorName,
			LocalDate neededBy,
			String state) {
	}
}
