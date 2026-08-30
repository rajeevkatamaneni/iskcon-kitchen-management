package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A gift from inside the app (E7-S2, E7-S6).
 *
 * <p>There is no name or email on this request, and that is the point: the temple already knows who
 * is giving, because they are signed in to it. Either arriving from the browser would be a claim
 * about identity that the server has no reason to believe when it can read the real one from the
 * token.
 *
 * <p>Address and PAN are the exception, and only for an 80G receipt: those the temple does not hold,
 * so they are the one thing a devotee still has to type. The service checks them (E7-S4).
 */
public record AccountDonationRequest(
		@NotNull @Positive BigDecimal amountInr,
		boolean wants80g,
		@Size(max = 500) String address,
		@Size(max = 10) String pan) {

	/** The donor this gift is from: the account, plus whatever an 80G certificate additionally needs. */
	public DonorDetails toDonor(org.iskcon.kms.auth.AuthenticatedUser actor) {
		DonorDetails account = DonorDetails.ofAccount(actor);
		if (!wants80g) {
			return account;
		}
		return new DonorDetails(account.name(), account.phone(), account.email(), address, pan, true);
	}
}
