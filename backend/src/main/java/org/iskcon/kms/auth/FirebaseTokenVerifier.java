package org.iskcon.kms.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verifies Firebase ID tokens against Google's public keys.
 *
 * <p>Only active when a {@link FirebaseAuth} bean exists — see {@code FirebaseConfiguration}.
 * Tests substitute a stub implementation so the suite never depends on a live Firebase project
 * or network access.
 */
@Component
@ConditionalOnProperty(name = "kms.firebase.enabled", havingValue = "true")
public class FirebaseTokenVerifier implements TokenVerifier {

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
			// checkRevoked = true costs an extra lookup but means a token invalidated by a
			// password reset or explicit revocation stops working immediately, rather than
			// remaining valid until it expires.
			FirebaseToken token = firebaseAuth.verifyIdToken(idToken, true);

			return new VerifiedSubject(
					token.getUid(),
					token.getEmail(),
					(String) token.getClaims().get("phone_number"));

		} catch (FirebaseAuthException e) {
			throw new InvalidTokenException("Token verification failed", e);
		}
	}
}
