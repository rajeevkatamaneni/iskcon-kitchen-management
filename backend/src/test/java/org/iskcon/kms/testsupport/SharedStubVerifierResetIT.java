package org.iskcon.kms.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier.InvalidTokenException;
import org.iskcon.kms.auth.TokenVerifier.VerifiedSubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Holds {@code AbstractIntegrationTest} to its promise that every test starts with nobody signed in.
 *
 * <p><strong>Why this class exists at all.</strong> Since T-189 about a hundred test classes share
 * one {@link StubTokenVerifier} through one Spring context, and JUnit does not fix the order they run
 * in. The reset in the base class is what stops one class's sign-in from being accepted in the next.
 * But no other test in the suite notices when that reset is missing: almost every class deletes its
 * users after each test, so a token left accepted for somebody who no longer has an account is refused
 * anyway, and the suite stays green over a leak it cannot see. That was checked rather than assumed,
 * by removing the reset and running the classes most likely to show it. So the promise gets a test of
 * its own, stated about the verifier directly, and a leak would name whoever was left signed in.
 *
 * <p>Declares no mocks, properties or configuration, so it runs in the suite's main shared context,
 * the one where a leak could come from the most other classes. The order is fixed on purpose:
 * the first test catches a sign-in left by any class that happened to run before this one, the second
 * leaves one behind, and the third catches it within the class, so it fails without the reset on
 * every run rather than only when some other class happened to run first.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SharedStubVerifierResetIT extends AbstractIntegrationTest {

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Test
	@Order(1)
	@DisplayName("a test starts with nobody signed in, whatever class ran before it")
	void startsSignedOut() {
		assertNobodyIsSignedIn();
	}

	@Test
	@Order(2)
	@DisplayName("a test may sign somebody in and leave them signed in when it ends")
	void signsSomebodyInAndLeavesThem() throws InvalidTokenException {
		stubVerifier.accept("uid-left-signed-in-by-order-2", "left@example.com", "+919000000009");

		assertThat(stubVerifier.verify(StubTokenVerifier.TOKEN).uid())
				.isEqualTo("uid-left-signed-in-by-order-2");
	}

	@Test
	@Order(3)
	@DisplayName("the next test starts with nobody signed in again")
	void theNextTestStartsSignedOutAgain() {
		assertNobodyIsSignedIn();
	}

	private void assertNobodyIsSignedIn() {
		VerifiedSubject leaked = null;
		try {
			leaked = stubVerifier.verify(StubTokenVerifier.TOKEN);
		} catch (InvalidTokenException expected) {
			// Nobody signed in: what every test should start from.
		}
		assertThat(leaked)
				.as("the shared StubTokenVerifier should accept nobody when a test starts, but it still "
						+ "accepts \"%s\" as %s, left signed in by an earlier test",
						StubTokenVerifier.TOKEN, leaked)
				.isNull();
		assertThat(stubVerifier.isEmpty())
				.as("the shared StubTokenVerifier should hold no accepted token when a test starts")
				.isTrue();
	}
}
