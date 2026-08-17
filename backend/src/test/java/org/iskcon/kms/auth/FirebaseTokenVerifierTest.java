package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which failures mean "this token is no good" and which mean "I could not ask".
 *
 * <p>They arrive as the same exception, and telling them apart is the whole of revocation. Getting
 * it wrong is silent in the dangerous direction: a revoked token verifies offline perfectly well,
 * because its signature was valid when it was issued and nothing about revocation is written into
 * it. So a verifier that falls back on every failure accepts every revoked token, and looks healthy
 * doing it.
 */
class FirebaseTokenVerifierTest {

	private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
	private final FirebaseTokenVerifier verifier = new FirebaseTokenVerifier(firebaseAuth);

	@Test
	@DisplayName("a token Firebase says is revoked is refused, not re-verified into acceptance")
	void revokedTokenIsRefused() throws Exception {
		// The revocation check ran and the answer was no.
		when(firebaseAuth.verifyIdToken(anyString(), eq(true)))
				.thenThrow(authException(AuthErrorCode.REVOKED_ID_TOKEN));
		// Offline verification would pass — that is exactly the trap.
		when(firebaseAuth.verifyIdToken(anyString())).thenReturn(mock(FirebaseToken.class));

		assertThatThrownBy(() -> verifier.verify("a-revoked-token"))
				.isInstanceOf(TokenVerifier.InvalidTokenException.class);
	}

	@Test
	@DisplayName("a disabled user's token is refused for the same reason")
	void disabledUserIsRefused() throws Exception {
		when(firebaseAuth.verifyIdToken(anyString(), eq(true)))
				.thenThrow(authException(AuthErrorCode.USER_DISABLED));
		when(firebaseAuth.verifyIdToken(anyString())).thenReturn(mock(FirebaseToken.class));

		assertThatThrownBy(() -> verifier.verify("a-disabled-users-token"))
				.isInstanceOf(TokenVerifier.InvalidTokenException.class);
	}

	@Test
	@DisplayName("a lookup that could not run leaves the offline verification standing")
	void unreachableRevocationFallsBack() throws Exception {
		// What a disabled Identity Toolkit API actually produces: no auth error code at all. Locking
		// every temple out over our own misconfiguration would be the worse failure.
		FirebaseToken token = mock(FirebaseToken.class);
		when(token.getUid()).thenReturn("uid-1");
		when(firebaseAuth.verifyIdToken(anyString(), eq(true))).thenThrow(authException(null));
		when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);

		assertThat(verifier.verify("a-good-token").uid()).isEqualTo("uid-1");
	}

	@Test
	@DisplayName("a token that fails offline too is refused whatever the reason was")
	void badTokenIsRefused() throws Exception {
		when(firebaseAuth.verifyIdToken(anyString(), eq(true))).thenThrow(authException(null));
		when(firebaseAuth.verifyIdToken(anyString())).thenThrow(authException(null));

		assertThatThrownBy(() -> verifier.verify("rubbish"))
				.isInstanceOf(TokenVerifier.InvalidTokenException.class);
	}

	@Test
	@DisplayName("no token at all is refused without asking Firebase anything")
	void missingTokenIsRefused() {
		assertThatThrownBy(() -> verifier.verify(null))
				.isInstanceOf(TokenVerifier.InvalidTokenException.class);
		assertThatThrownBy(() -> verifier.verify("  "))
				.isInstanceOf(TokenVerifier.InvalidTokenException.class);
	}

	private static FirebaseAuthException authException(AuthErrorCode authErrorCode) {
		return new FirebaseAuthException(ErrorCode.INVALID_ARGUMENT, "test", null, null, authErrorCode);
	}
}
