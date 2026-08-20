package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One advance being repaid out of a payment (B8).
 *
 * <p>The link is to the advance itself, not to a bare figure, which is what lets the balance fall
 * out of the entries: dock ₹2,000 against a particular advance and that advance is ₹2,000 nearer
 * settled, without anybody adjusting a total.
 *
 * <p>The amount carries no {@code @Positive}: a deduction of zero is a mistake with its own
 * message (KMS-4007), and routing it through the generic validation failure would tell the admin
 * only that "some of the information isn't valid".
 */
public record PaymentDeductionRequest(

		@NotNull(message = "Choose which advance this repays.")
		UUID advanceId,

		@NotNull(message = "Enter how much of the advance this payment repays.")
		BigDecimal amount) {
}
