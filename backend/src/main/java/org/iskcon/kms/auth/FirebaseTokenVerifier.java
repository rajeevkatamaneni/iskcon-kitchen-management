package org.iskcon.kms.auth;

import com.google.firebase.auth.AuthErrorCode;
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
			// The combined call failed, and *why* decides everything. Firebase reports a token it has
			// been told to stop trusting through the same exception as a lookup that could not run,
			// and for a long time this told them apart by not trying: it re-verified offline, which a
			// revoked token passes — its signature is perfectly good — and accepted it. Revocation
			// was unenforceable, and would have stayed so even once the Identity Toolkit API was
			// enabled, because the answer was being thrown away rather than never arriving.
			if (isTokenItself(withRevocation.getAuthErrorCode())) {
				log.warn("Refusing a token Firebase has revoked or disabled (code={})",
						withRevocation.getAuthErrorCode());
				throw new InvalidTokenException("Token verification failed", withRevocation);
			}

			// Anything else is the lookup, not the token: a missing IAM binding, a quota-project
			// quirk, the Auth project mid-restore. Treating that as a bad token would lock every
			// temple out over an operational fault, so the offline verification stands and says so.
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

	/**
	 * Whether Firebase is telling us about the token rather than about itself.
	 *
	 * <p>These are answers, and answers are to be obeyed. Everything else — including a null code,
	 * which is what a disabled Identity Toolkit API produces — is Firebase failing to answer, and a
	 * failure to answer must not sign anybody out.
	 */
	private static boolean isTokenItself(AuthErrorCode code) {
		return code == AuthErrorCode.REVOKED_ID_TOKEN
				|| code == AuthErrorCode.USER_DISABLED
				|| code == AuthErrorCode.EXPIRED_ID_TOKEN
				|| code == AuthErrorCode.INVALID_ID_TOKEN;
	}

	private static VerifiedSubject toSubject(FirebaseToken token) {
		return new VerifiedSubject(
				token.getUid(),
				token.getEmail(),
				(String) token.getClaims().get("phone_number"),
				token.isEmailVerified());
	}
}
