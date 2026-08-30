package org.iskcon.kms.donation;

/**
 * The donor behind an online gift (E7-S4), on one of two paths:
 * <ul>
 *   <li>named — the account's own name and contact, {@code wants80g=false};
 *   <li>80G — the same, plus address and PAN, only where the tenant is 80G-approved.
 * </ul>
 * The service enforces the shape; this record just carries the choice.
 *
 * <p>There was a third path until 2026-08-29, and it was the first: anonymous, keeping no personal
 * information at all. It existed because a stranger could give without an account. Giving now
 * requires a signed-in devotee, so there is nobody left for it to describe — the temple already
 * holds the name of everyone who can reach the form.
 *
 * <p>The record carried a {@code consent} flag with it, for the data-use notice a public form had
 * to capture. That is gone for the same reason: consent to hold a name the temple already holds is
 * not a thing to ask for at checkout. A consent timestamp is still stamped on the donation, because
 * the receipt is a use of that name and the ledger should say when it was given.
 */
public record DonorDetails(
		String name,
		String phone,
		String email,
		String address,
		String pan,
		boolean wants80g) {

	/**
	 * The donor a signed-in devotee already is.
	 *
	 * <p>Nothing here was typed into a form: the temple holds this person's name, phone and email
	 * because they belong to it, and the gift is being made from inside their own account.
	 */
	public static DonorDetails ofAccount(org.iskcon.kms.auth.AuthenticatedUser actor) {
		return new DonorDetails(actor.getFullName(), actor.getPhone(), actor.getEmail(), null, null, false);
	}
}
