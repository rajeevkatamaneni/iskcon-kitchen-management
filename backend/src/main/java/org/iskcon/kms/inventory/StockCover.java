package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Roughly how long what is on the shelf lasts, at the rate the temple has actually been getting
 * through it (T-432).
 *
 * <p><strong>A {@code null} where one of these is expected is the honest answer, not a missing
 * one.</strong> {@code StockItemView.lastsFor} is null whenever
 * {@link StockFactsService#dailyUseBaseByIngredient} declined to judge — see the rule written out
 * there — and the screen says so in words rather than printing a figure it cannot stand behind.
 * Rajeev, 2026-09-20, asked for this in the same breath as the estimate itself: <em>"where there is
 * not enough history to judge, it must say so honestly rather than guess"</em>. An item cooked with
 * twice in a month is not a trend.
 *
 * <p>Every field here is an <em>approximation</em> and the screen is required to say the word. The
 * estimate is a division of two figures that are each honest on their own — what the store holds,
 * and how much has left it per day over the observed period — and the quotient is not: a festival
 * next week, a delivery tomorrow or a meal taken off the plan all move it, and none of them is in
 * the arithmetic.
 *
 * @param days how many days the on-hand figure lasts at {@code perDay}, rounded down so the
 *             estimate never promises a day the shelf may not have. Capped at
 *             {@link StockFactsService#COVER_CAP_DAYS}; see {@code beyondWindow}.
 * @param beyondWindow true when the shelf lasts longer than the period the estimate was judged
 *             from, in which case {@code days} is the cap rather than a computed figure and the
 *             screen says "more than" rather than a number. Dividing 90 days of evidence into a
 *             four-year answer is arithmetic, not knowledge.
 * @param runsOutOn the temple's own day the stock is expected to run out, or null when
 *             {@code beyondWindow} — there is no honest date past the end of the evidence.
 * @param perDay how much leaves the store on an average day, in the ingredient's canonical unit.
 *             Carried so the item's own screen can show the working behind the estimate rather than
 *             asking anybody to take it on trust.
 */
public record StockCover(
		int days,
		boolean beyondWindow,
		LocalDate runsOutOn,
		BigDecimal perDay) {
}
