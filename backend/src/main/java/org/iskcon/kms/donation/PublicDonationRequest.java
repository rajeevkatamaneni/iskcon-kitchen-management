package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A donor's one-time donation from the public page (E7-S2), carrying the amount and the E7-S4 donor
 * path. No account, no tenant id — the tenant is resolved server-side from the page's slug.
 */
public record PublicDonationRequest(
		@NotNull @Positive BigDecimal amountInr,
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
