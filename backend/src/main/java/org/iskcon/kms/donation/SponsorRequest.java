package org.iskcon.kms.donation;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A donor sponsoring some units of a wish-list item (E7-S6), with the E7-S4 donor path. Amount is
 * derived server-side from the item price × quantity — never sent by the client.
 */
public record SponsorRequest(
		/** Whole units to sponsor. Ignored when {@link #amountInr} is given. */
		int quantity,

		/** Rupees towards the item instead of whole units — "give ₹500", "cover the rest". */
		@Positive java.math.BigDecimal amountInr,

		boolean anonymous,
		@Size(max = 200) String name,
		@Size(max = 20) String phone,
		@Size(max = 200) String email,
		@Size(max = 500) String address,
		@Size(max = 10) String pan,
		boolean wants80g,
		boolean consent) {

	public DonorDetails toDonor() {
		return new DonorDetails(anonymous, name, phone, email, address, pan, wants80g, consent);
	}
}
