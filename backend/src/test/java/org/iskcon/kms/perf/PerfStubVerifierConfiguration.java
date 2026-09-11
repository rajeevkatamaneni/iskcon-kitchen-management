package org.iskcon.kms.perf;

import java.util.HashMap;
import java.util.Map;
import org.iskcon.kms.auth.TokenVerifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * One stub verifier for the whole {@code perf} package, deliberately shared.
 *
 * <p>Almost every integration class in this suite declares a private nested copy of this
 * configuration, and {@code build.gradle.kts} records what that costs: an imported configuration
 * class is part of the TestContext framework's cache key, so each private copy asks for a Spring
 * context of its own, and a full run ends up holding eighty-one of them alive at once. These classes
 * do not need to add two more to that pile, so they share one.
 */
@TestConfiguration
public class PerfStubVerifierConfiguration {

	@Bean
	@Primary
	PerfStubTokenVerifier perfStubTokenVerifier() {
		return new PerfStubTokenVerifier();
	}

	/**
	 * Records the SQL one request issues (T-140). Off until a test calls
	 * {@link StatementRecorder#start()}, so it costs this context a delegating call per connection
	 * and nothing else — which is why it lives in the shared configuration rather than in a second
	 * one that would fork the context cache.
	 */
	@Bean
	static BeanPostProcessor perfStatementRecorder() {
		return StatementRecorder.wrapTheDataSource();
	}

	/** Accepts the single token {@code perf-token} for whichever uid was last signed in. */
	static class PerfStubTokenVerifier implements TokenVerifier {

		static final String TOKEN = "perf-token";

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put(TOKEN, new VerifiedSubject(uid, uid + "@example.com", "+919000000001"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
