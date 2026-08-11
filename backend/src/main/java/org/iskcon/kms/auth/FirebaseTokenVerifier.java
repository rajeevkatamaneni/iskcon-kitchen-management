package org.iskcon.kms.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verifies Firebase ID tokens against Google's public keys.
 *
 * <p>Only active when a {@link FirebaseAuth} bean exists — see {@code FirebaseConfiguration}.
 * Tests substitute a stub implementation so the suite never depends on a live Firebase project
 * or network access.
 *
 * <p>Verification is two things with different failure modes, deliberately separated:
 *
 * <ul>
 *   <li><b>Offline</b> — signature, audience and expiry, checked against Google's cached public
 *       keys. No network to our Firebase project, no permissions. If this fails the token is not
 *       trustworthy and the request is rejected.
 *   <li><b>Revocation</b> — an online lookup against the Firebase Auth project so a token killed by
 *       a password reset or explicit revocation stops working immediately. This call is
 *       cross-project (Auth lives in its own project) and can fail for reasons that have nothing to
 *       do with the token — a missing IAM binding, a quota-project quirk, the project mid-restore.
 *       Treating such a failure as "bad token" would lock everyone out over an operational issue,
 *       so revocation is <em>best-effort</em>: if the lookup itself cannot run, we log it loudly and
 *       accept the offline-verified token (it stays valid until it expires, ~1h).
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "kms.firebase.enabled", havingValue = "true")
public class FirebaseTokenVerifier implements TokenVerifier {

	private static final Logger log = LoggerFactory.getLogger(FirebaseTokenVerifier.class);

	private final FirebaseAuth firebaseAuth;

	public FirebaseTokenVerifier(FirebaseAuth firebaseAuth) {
		this.firebaseAuth = firebaseAuth;
	}

	@Override
	public VerifiedSubject verify(String idToken) throws InvalidTokenException {
		if (idToken == null || idToken.isBlank()) {
			throw new InvalidTokenException("No token supplied");
		}

		try {
			return toSubject(firebaseAuth.verifyIdToken(idToken, true));

		} catch (FirebaseAuthException withRevocation) {
			// The combined call failed. It might be a genuinely bad token, or only the revocation
			// lookup that couldn't run. Re-verify offline to tell them apart: if offline passes, the
			// token is trustworthy and it was the revocation check that failed — accept it, loudly.
			log.warn(
					"Revocation-checked verification failed (code={}, msg={}); re-verifying offline",
					withRevocation.getAuthErrorCode(), withRevocation.getMessage());
			try {
				VerifiedSubject subject = toSubject(firebaseAuth.verifyIdToken(idToken));
				log.warn(
						"Accepting offline-verified token for uid {} — revocation could not be checked;"
								+ " fix the cross-project Firebase Auth access to restore it",
						subject.uid());
				return subject;
			} catch (FirebaseAuthException offline) {
				log.warn(
						"Offline token verification also failed (code={}, msg={})",
						offline.getAuthErrorCode(), offline.getMessage());
				throw new InvalidTokenException("Token verification failed", offline);
			}
		}
	}

	private static VerifiedSubject toSubject(FirebaseToken token) {
		return new VerifiedSubject(
				token.getUid(),
				token.getEmail(),
				(String) token.getClaims().get("phone_number"),
				token.isEmailVerified());
	}
}
