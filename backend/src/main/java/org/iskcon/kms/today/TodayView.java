package org.iskcon.kms.today;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.meal.MealCrewView;

/**
 * The temple's morning screen, in one payload (E4-S8).
 *
 * <p>Assembled from what the rest of the product already knows — meals, stock, the roster, vendors'
 * prices, deliveries — because this screen is the first thing loaded each morning, often on a phone
 * on a temple's connection. Six requests to draw one screen is a slow start to the day.
 *
 * <p>Nullable fields are the honest way to say "not for you": kitchen staff do not hold
 * {@code MANAGE_VENDOR_PAYMENTS}, so the money side of {@link #deliveries()} is absent for them
 * rather than zeroed. A zero would read as a statement about the world, which is a different and
 * wrong thing to say.
 *
 * @param date     the temple's today, in its own timezone.
 * @param calendar what the calendar says about today and tomorrow, or null where a temple has no
 *                 calendar computed yet.
 * @param unrecordedMeals how many meals from the past week nobody has typed the job card back in
 *                 for. A nudge, not an alarm — but stock silently overstates itself until somebody
 *                 does, because a meal is only drawn from the store room when it is recorded (§2).
 * @param materialsCost what today's planned food is costing, estimated from vendors' last-known
 *                 prices. Replaces month-to-date giving, which moved to the donations screen where
 *                 somebody goes to look at money deliberately (build brief §8, §9).
 * @param itemsTracked how many consumables the temple tracks at all. A temple tracking nothing has
 *                 nothing below par either, and "0 items below par" would read as reassurance when
 *                 it means the opposite — so the screen needs to tell the two apart.
 * @param shiftsAhead  likewise: no unfilled spots because every shift is full is a different
 *                 statement from no unfilled spots because no shift has been posted.
 */
public record TodayView(
		LocalDate date,
		CalendarNote calendar,
		List<Meal> meals,
		int platesToday,
		int itemsBelowThreshold,
		int itemsTracked,
		Workforce workforce,
		MaterialsCost materialsCost,
		int unrecordedMeals,
		List<Delivery> deliveries) {

	/**
	 * What today and tomorrow ask of the kitchen. Tomorrow matters as much as today: a fast changes
	 * every menu on it, and menus are settled the day before.
	 *
	 * @param fastingToday    today excludes grains and beans.
	 * @param fastingTomorrow the same, for tomorrow — the banner case.
	 * @param tithi           today's place in the lunar month; the screen names the day by it the way
	 *                        a pujari does. Numeric here, named on the client, like the calendar.
	 * @param ahead           the next day after tomorrow that the kitchen has to cook differently for,
	 *                        or null when the month ahead holds none. Ordering a week's vegetables on
	 *                        the day a fast is announced is too late.
	 */
	public record CalendarNote(
			boolean fastingToday,
			boolean fastingTomorrow,
			String todayName,
			String tomorrowName,
			LocalTime sunrise,
			int tithi,
			int paksa,
			int masa,
			Integer naksatra,
			Ahead ahead) {
	}

	/**
	 * A day the kitchen must prepare for, and how far off it is.
	 *
	 * @param kind FAST or FESTIVAL — the two ask opposite things: one takes food off the menu, the
	 *             other adds people to the hall.
	 */
	public record Ahead(LocalDate date, String name, String kind, int daysAway) {
	}

	/**
	 * A meal of today — a kind and a time, with its dishes beneath — in the order the kitchen works:
	 * by the time its food must be ready.
	 *
	 * <p>A meal, not a dish. Today used to list one row per preparation, so a lunch of three dishes
	 * read as three lunches; grouped, the screen says what the kitchen actually has to produce
	 * (build brief A3).
	 *
	 * @param plates   what this meal scales to, from its head count. Never the sum of its dishes: a
	 *                 lunch of three dishes at 250 servings each is 250 plates, not 750 (A4, §1d).
	 * @param recorded whether the returned job card has been typed in. Today says this out loud
	 *                 rather than badging it — "not yet recorded" is a fact about the store room,
	 *                 since a meal nobody records is stock that never left (§2).
	 */
	public record Meal(
			String mealKind,
			LocalTime readyBy,
			int plates,
			boolean recorded,
			boolean awaitingRecord,
			String occasionName,
			List<Dish> dishes) {
	}

	/** One preparation within a meal. */
	public record Dish(
			UUID id,
			String recipeName,
			BigDecimal targetYield,
			BigDecimal actualServings,
			boolean notMade,
			String status) {
	}

	/**
	 * Whether there is enough of a kitchen to cook with today (B1, extended by items 19 and 24).
	 *
	 * <p>Staff and volunteers are counted apart and never summed <em>for the day</em> — a full-time
	 * cook and a two-hour evening volunteer are not interchangeable, and a single number would hide
	 * which of them is missing.
	 *
	 * <p>Per meal is a different question with a different answer. {@code Working today · 7} says
	 * nothing about whether lunch has enough hands, because the seven are not all there at midday and
	 * lunch may take eight. {@code meals} is the line that replaces it — <em>Breakfast 4 of 4 · Lunch
	 * 5 of 8 · Dinner 6 of 6</em> — with the short one standing out. A meal counts somebody if their
	 * working window covers the time its food must be ready, and it does not care whether they are
	 * staff or a volunteer.
	 *
	 * @param meals one readout per meal the kitchen is cooking today, in the order it works. Empty on
	 *              a day with nothing planned, which is a day with nothing to be short for.
	 */
	public record Workforce(int staffIn, int volunteers, List<MealCrewView> meals) {
	}

	/**
	 * What today's food is costing, estimated (B2, §9).
	 *
	 * @param withoutPrice how many ingredients in today's basket have no known price. Named rather
	 *                     than swallowed: a total that silently omits a third of the basket is worse
	 *                     than one that admits the gap.
	 */
	public record MaterialsCost(BigDecimal estimatedTotal, int withoutPrice) {
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
