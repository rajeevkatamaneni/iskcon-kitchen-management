package org.iskcon.kms.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Registers the suite's one {@link StubTokenVerifier}, ahead of the application's rejecting verifier.
 *
 * <p>Imported by {@code AbstractIntegrationTest} and by nothing else. That is the whole point: an
 * imported configuration class is part of Spring's test-context cache key, and so is a test class's
 * nested {@code @TestConfiguration}, so the only way for a hundred classes to share a context is for
 * all of them to inherit the same import and declare no nested configuration of their own.
 *
 * <p>Deliberately holds nothing but the verifier. A stub that changes what the application does for
 * everyone — a geocoder, a payment probe, a recording {@code DataSource} — belongs in the one test
 * class that needs it, at the cost of that class keeping a context of its own, rather than here, where
 * it would silently change what every other test is testing.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubVerifierConfiguration {

	@Bean
	@Primary
	StubTokenVerifier stubTokenVerifier() {
		return new StubTokenVerifier();
	}
}
