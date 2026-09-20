package org.iskcon.kms.testsupport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.iskcon.kms.auth.TokenVerifier;

/**
 * The one stub token verifier the whole integration suite signs in through.
 *
 * <p><strong>Why there is exactly one.</strong> Until T-189, 99 test classes each declared a private
 * nested copy of this class and of a {@code @TestConfiguration} that registered it, 97 of them
 * byte-for-byte identical. Spring Boot puts a test class's nested {@code @TestConfiguration} into the
 * context cache key, so every copy asked for an application context of its own: a full run built 126
 * of them and ended holding 1273 MB, against a 2 GB ceiling, with CI dying of heap and not one
 * assertion failing. One shared class, registered once by {@code AbstractIntegrationTest}, lets every
 * class with the same mocks and properties share one context.
 *
 * <p><strong>Why it is safe to share, and what makes it so.</strong> The accepted tokens are mutable
 * state that now lives as long as a context, and a context now outlives many test classes whose order
 * JUnit does not fix. So {@code AbstractIntegrationTest} resets this before every test, and no test
 * class resets it in its own setup any more. A test that signs somebody in therefore cannot leave them
 * signed in for the next test, in its own class or any other. That reset is load-bearing and the
 * negative control in {@code docs/work/proof/T-189.md} shows a real test going red without it.
 *
 * <p>The overloads are the union of what the private copies offered, each producing exactly the
 * subject its old copy did, so no test signs in as anyone different from before.
 *
 * <p><strong>It also completes the fixture of a cook (T-361).</strong> Being the one place every test
 * class's sign-in passes through, it is also the one place that can give a Kitchen Staff or Kitchen
 * Manager account the employment record a real one always has, which the meal planner's kitchen rule
 * now requires. {@link TestStaffRecords} says what is written and why it is written here; a class whose
 * subject is a cook who was never hired calls {@link #withoutAutomaticStaffRecords()}.
 */
public class StubTokenVerifier implements TokenVerifier {

	/** The bearer token {@link #accept(String)} and its siblings register. */
	public static final String TOKEN = "valid-token";

	private final Map<String, VerifiedSubject> accepted = new HashMap<>();

	/**
	 * Completes the fixture of a signed-in cook: see {@link TestStaffRecords} for the whole reason.
	 * It is asked at {@link #verify} rather than at {@link #accept} because a test's {@code users} row
	 * may be written either side of the sign-in, and only by the time a request arrives is it certainly
	 * there.
	 */
	private final TestStaffRecords staffRecords;

	/** Uids already looked at this test, so one test's several requests cost one query, not one each. */
	private final Set<String> ensured = new HashSet<>();

	/** Off for a class that is testing a cook who was never hired; back on for the next test. */
	private boolean automaticStaffRecords = true;

	public StubTokenVerifier(TestStaffRecords staffRecords) {
		this.staffRecords = staffRecords;
	}

	/** Accepts {@link #TOKEN} for {@code uid}, with the email and phone most tests never look at. */
	public void accept(String uid) {
		accept(uid, uid + "@example.com", "+919000000000");
	}

	/** Accepts {@link #TOKEN} for this identity, with the email unverified. */
	public void accept(String uid, String email, String phone) {
		accept(TOKEN, new VerifiedSubject(uid, email, phone));
	}

	/** Accepts {@link #TOKEN} for this identity, stating whether the email is verified. */
	public void accept(String uid, String email, String phone, boolean emailVerified) {
		accept(TOKEN, new VerifiedSubject(uid, email, phone, emailVerified));
	}

	/** Accepts {@link #TOKEN} for a subject the test built itself. */
	public void accept(VerifiedSubject subject) {
		accept(TOKEN, subject);
	}

	/** Accepts a token of the test's choosing, for tests that sign several people in at once. */
	public void accept(String token, VerifiedSubject subject) {
		accepted.put(token, subject);
	}

	/** Accepts {@link #TOKEN} for {@code uid} with a verified email, as a claim of an invitation needs. */
	public void acceptVerified(String uid, String email) {
		accept(uid, email, "+919000000000", true);
	}

	/**
	 * Stops this test giving a signed-in Kitchen Staff or Kitchen Manager account an employment record
	 * it does not have ({@link TestStaffRecords}). For the classes whose subject is exactly that person
	 * — somebody with a login and no hire. Called from the class's own {@code @BeforeEach}, because
	 * {@link #reset()} turns it back on before every test.
	 */
	public void withoutAutomaticStaffRecords() {
		automaticStaffRecords = false;
	}

	/** Forgets every accepted token. Called by {@code AbstractIntegrationTest} before each test. */
	public void reset() {
		accepted.clear();
		ensured.clear();
		automaticStaffRecords = true;
	}

	/** Whether nobody is signed in, for helpers that send a bearer token only when somebody is. */
	public boolean isEmpty() {
		return accepted.isEmpty();
	}

	@Override
	public VerifiedSubject verify(String idToken) throws InvalidTokenException {
		VerifiedSubject subject = accepted.get(idToken);
		if (subject == null) {
			throw new InvalidTokenException("Unrecognised token");
		}
		if (automaticStaffRecords && ensured.add(subject.uid())) {
			staffRecords.ensureFor(subject.uid());
		}
		return subject;
	}
}
