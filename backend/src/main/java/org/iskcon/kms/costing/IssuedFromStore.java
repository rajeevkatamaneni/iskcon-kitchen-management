package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the temple store issued to each kitchen over a period, costed (E10-S13).
 *
 * <p>An issue already records which kitchen the food went to, which makes it a cost attribution the
 * temple was not using as one. This is that reading of the existing ledger and nothing more: no new
 * table, no new noun, and no cost centre alongside the kitchen — the kitchen <em>is</em> the cost
 * centre until somebody can name a case where the two differ.
 *
 * <p><strong>Why a kitchen is costed this way at all.</strong> A kitchen that plans its meals here is
 * costed through what it cooked; a kitchen that does not plan meals here can only be costed through
 * what it was issued. The Deity kitchen is the second sort by design (E10 D5, one kitchen one door),
 * so issues are not a rough proxy for its cost — they are the only measurement that exists.
 *
 * <p><strong>And what the figure is not.</strong> See {@link KitchenIssueCost}: a kitchen may buy
 * food itself, and this report will never see it. Every figure here is a floor.
 *
 * @param kitchens one row per kitchen the store issued to in the period, dearest first. A kitchen
 *                 that was issued nothing does not appear — a row of zeroes is not a finding.
 */
public record IssuedFromStore(
		LocalDate from,
		LocalDate to,
		int requests,
		BigDecimal estimatedTotal,
		int ingredientsWithoutPrice,
		List<UnpricedIngredient> unpriced,
		List<KitchenIssueCost> kitchens) {
}
