package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A signed-in donor setting up a recurring donation (E7-S3). Frequency is one of the provider's
 * supported intervals; the donor path (name/80G) follows E7-S4, but recurring is never anonymous —
 * a mandate needs an identity.
 */
public record CreateRecurringRequest(
		@NotNull Frequency frequency,
		@NotNull @Positive BigDecimal amountInr,
		@Size(max = 200) String name,
		@Size(max = 20) String phone,
		@Size(max = 200) String email,
		@Size(max = 500) String address,
		@Size(max = 10) String pan,
		boolean wants80g,
		boolean consent) {

	public enum Frequency { WEEKLY, MONTHLY, QUARTERLY, ANNUALLY }
}
