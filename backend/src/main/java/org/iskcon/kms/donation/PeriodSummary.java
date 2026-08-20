package org.iskcon.kms.donation;

import java.util.List;
import java.util.Map;

/**
 * The donations ledger's tiles for one chosen period, each with its year-on-year comparison.
 *
 * <p>The window is handed back rather than assumed, because the same two dates then drive the rows
 * beneath the tiles and the CSV the accountant downloads. A screen that worked out its own dates
 * would eventually disagree with the server about where the financial year starts, and the export
 * would quietly cover a different span from the figures above it.
 *
 * @param window                 the chosen window and the one a year earlier it is measured against
 * @param hasPriorYear           false when the temple's first recorded gift falls after the prior
 *                               window closed — there is nothing to compare with, which is a
 *                               different statement from having compared and found nothing
 * @param byCategory             keyed by ONE_TIME / RECURRING / WISHLIST / MANUAL / IN_KIND, and
 *                               carrying every category that had money in either window, so a kind
 *                               of giving that has stopped still says so instead of vanishing
 * @param financialYearsWithGifts the opening years the period picker may offer, newest first
 */
public record PeriodSummary(
		LedgerPeriod window,
		boolean hasPriorYear,
		Map<String, CategoryComparison> byCategory,
		List<Integer> financialYearsWithGifts) {
}
