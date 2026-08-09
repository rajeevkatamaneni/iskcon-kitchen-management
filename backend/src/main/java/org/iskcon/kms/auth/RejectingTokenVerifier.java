package org.iskcon.kms.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fallback verifier used when Firebase is not configured — local development against a database
 * only, and tests that do not exercise authentication.
 *
 * <p>Rejects every token rather than accepting them. An unconfigured system that let callers in
 * would be far more dangerous than one that lets nobody in: the failure is loud and obvious
 * instead of silently authenticating strangers. Missing security configuration should break
 * things, not quietly disable the protection.
 */
@Component
@ConditionalOnProperty(name = "kms.firebase.enabled", havingValue = "false", matchIfMissing = true)
public class RejectingTokenVerifier implements TokenVerifier {

	@Override
	public VerifiedSubject verify(String idToken) throws InvalidTokenException {
		throw new InvalidTokenException(
				"Authentication is not configured (kms.firebase.enabled is false)");
	}
}
