package org.iskcon.kms.donation;

import java.math.BigDecimal;

/**
 * One row of the Form 10BD-shaped dataset (E7-S4). Export is Phase 2; this is the shape the captured
 * 80G donations already satisfy by construction, so the Phase-2 export is a formatting job, not a
 * data-gathering one.
 */
public record Form10bdRow(
		String donorName,
		String donorAddress,
		String pan,
		BigDecimal amountInr,
		String paymentMode,
		String section) {
}
