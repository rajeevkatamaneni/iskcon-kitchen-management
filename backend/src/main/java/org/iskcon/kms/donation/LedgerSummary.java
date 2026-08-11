package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * The ledger's summary cards (E7-S7): totals by category for the current month and the Indian
 * financial year (Apr–Mar) to date. The FY boundary matters for 80G-year alignment.
 */
public record LedgerSummary(
		LocalDate financialYearStart,
		Map<String, BigDecimal> monthToDateByCategory,
		Map<String, BigDecimal> financialYearToDateByCategory) {
}
