package org.iskcon.kms.donation;

import java.math.BigDecimal;

/**
 * One tile on the donations ledger: what this kind of gift came to in the chosen window, and what it
 * came to by the same point a year earlier.
 *
 * <p>{@code changePercent} is null whenever a percentage would be a lie rather than a figure. There
 * are two such cases and they read differently on screen, so the record keeps them apart by carrying
 * {@code previousTotal} alongside: a prior window of zero has no denominator, and "up ∞%" is not a
 * thing to put in front of an accountant — the screen says "nothing at this point last year"
 * instead. A temple whose records do not reach back that far has no prior window at all, which is
 * the summary's {@code hasPriorYear}, not this record's business.
 */
public record CategoryComparison(
		BigDecimal total,
		BigDecimal previousTotal,
		Integer changePercent) {
}
