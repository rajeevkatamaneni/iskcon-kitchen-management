package org.iskcon.kms.ban;

import java.time.LocalDate;

/**
 * What an Aadhaar card proves, without the Aadhaar number (B9).
 *
 * <p>The offline eKYC QR printed on an Aadhaar card is signed by UIDAI. Reading it yields the
 * holder's name, date of birth and the last four digits of the number, all attested by the issuer.
 * That triple is a <em>better</em> identity signal than a typed Aadhaar number, because a typed
 * number is only as good as the person typing it and this cannot be fabricated at all — and it is
 * not the number, so nothing here or in the database ever holds one.
 *
 * <p><b>The QR capture is not in this build.</b> This record is the seam it lands on. A future
 * {@code AadhaarQrVerifier} would parse and verify the signed payload and hand back one of these;
 * {@code RaiseEmploymentBanRequest.aadhaar()} and the hire check both already accept it, and
 * {@code BanMatcher}'s Aadhaar arm already matches on it. Until that reader exists the field is
 * always null and the arm is inert — which is why it is built as a value the caller supplies rather
 * than as something this package goes and fetches.
 *
 * @param name         the holder's name exactly as UIDAI gives it
 * @param dateOfBirth  the holder's date of birth as UIDAI gives it
 * @param last4        the last four digits of the Aadhaar number, and never more than four
 */
public record AadhaarIdentity(String name, LocalDate dateOfBirth, String last4) {

	/** True when all three parts are present. Two thirds of the triple is a false confidence. */
	public boolean isComplete() {
		return name != null && !name.isBlank() && dateOfBirth != null && last4 != null && last4.matches("[0-9]{4}");
	}
}
