package org.iskcon.kms.auth;

/**
 * Verifies an identity token and returns the subject it proves.
 *
 * <p>An interface rather than a direct Firebase call so the authentication filter can be tested
 * without a live Firebase project, and so a change of identity provider is a change of one
 * implementation rather than a change throughout the codebase.
 */
public interface TokenVerifier {

	/**
	 * @param idToken the bearer token from the Authorization header
	 * @return the verified subject
	 * @throws InvalidTokenException if the token is missing, malformed, expired, or not
	 *     verifiable — never distinguished further, since telling a caller precisely why their
	 *     token failed helps an attacker more than a user.
	 */
	VerifiedSubject verify(String idToken) throws InvalidTokenException;

	/**
	 * The identity a token proves. Notably does not include a role or tenant: a valid token
	 * shows someone controls an email or phone number, nothing about what they may do here.
	 * Authorisation is resolved from our own user record.
	 *
	 * <p>{@code emailVerified} matters for the first–sign-in account claim: a pending account is
	 * only bound to a real uid when the matching contact is one Firebase has actually verified. A
	 * present {@code phoneNumber} is already proof of OTP verification (Firebase populates it only
	 * after phone auth); an email is trustworthy only when {@code emailVerified} is true.
	 */
	record VerifiedSubject(String uid, String email, String phoneNumber, boolean emailVerified) {

		/**
		 * Convenience for callers that do not care about verification status — chiefly test stubs
		 * authenticating an already-linked user by uid, where no claim is attempted. Defaults
		 * {@code emailVerified} to false so an unverified email is never mistaken for a verified
		 * one; real verifiers must set it explicitly.
		 */
		public VerifiedSubject(String uid, String email, String phoneNumber) {
			this(uid, email, phoneNumber, false);
		}
	}

	class InvalidTokenException extends Exception {
		public InvalidTokenException(String message) {
			super(message);
		}

		public InvalidTokenException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
