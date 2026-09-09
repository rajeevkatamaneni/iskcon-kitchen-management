package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
		/**
		 * The donor's number, held to E.164 like every other person's number in this system.
		 *
		 * <p>Until T-021 this was a length check and nothing else, so "98450" was stored as a
		 * phone number on a mandate that renews for years — and this is the worst path in the
		 * application to accept one on, because a recurring plan is precisely the donation the
		 * temple has to reach somebody about later: a failed cycle, a changed amount, a receipt.
		 * Optional as before ({@code @Pattern} passes null), because the donor's account may
		 * already carry the number; what is refused now is a number that could never be dialled.
		 */
		@Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		@Size(max = 20) String phone,
		@Size(max = 200) String email,
		@Size(max = 500) String address,
		@Size(max = 10) String pan,
		boolean wants80g,
		boolean consent) {

	public enum Frequency { WEEKLY, MONTHLY, QUARTERLY, ANNUALLY }
}
