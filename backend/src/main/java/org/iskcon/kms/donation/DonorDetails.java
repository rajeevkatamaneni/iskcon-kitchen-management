package org.iskcon.kms.donation;

/**
 * The donor's identity choice at checkout (E7-S4), one of three paths:
 * <ul>
 *   <li>anonymous — {@code anonymous=true}, everything else ignored, zero PII retained;
 *   <li>named, no 80G — a name (and optional contact), {@code wants80g=false};
 *   <li>80G — name, address and PAN, {@code wants80g=true}, only where the tenant is 80G-approved.
 * </ul>
 * Non-anonymous paths require {@code consent} (the DPDP data-use notice). The service enforces the
 * shape; this record just carries the choice.
 */
public record DonorDetails(
		boolean anonymous,
		String name,
		String phone,
		String email,
		String address,
		String pan,
		boolean wants80g,
		boolean consent) {

	public static DonorDetails anonymousDonor() {
		return new DonorDetails(true, null, null, null, null, null, false, false);
	}
}
