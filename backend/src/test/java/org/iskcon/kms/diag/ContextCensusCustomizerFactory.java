package org.iskcon.kms.diag;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Hands every test context the one customizer that tells {@link ContextCensus} a context was built.
 *
 * <p>Registered for the test source set only, through {@code src/test/resources/META-INF/spring.factories}.
 *
 * <p><strong>It returns null unless the census is switched on.</strong> A {@code ContextCustomizer}
 * participates in the context cache key, so a customizer that was always present would be an
 * instrument that perturbs its own measurement — and worse, would do so on every ordinary run of the
 * suite, for every other task that depends on this suite to verify itself. Off means absent, not
 * merely quiet.
 *
 * <p>When it is on, all contexts receive the same singleton, so all keys shift by the same amount
 * and the number of distinct keys — which is what the census counts — is exactly what it would have
 * been.
 */
public class ContextCensusCustomizerFactory implements ContextCustomizerFactory {

	private static final ContextCustomizer INSTANCE = new CensusCustomizer();

	@Override
	public ContextCustomizer createContextCustomizer(
			Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
		return ContextCensus.enabled() ? INSTANCE : null;
	}

	/**
	 * Equality is by class, not by identity, so that the cache key is stable even if the framework
	 * were to build a second factory. Two customizers that compare equal keep two test classes
	 * sharing one context; two that do not would silently double the context count.
	 */
	private static final class CensusCustomizer implements ContextCustomizer {

		@Override
		public void customizeContext(
				ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
			ContextCensus.record(context, mergedConfig);
		}

		@Override
		public boolean equals(Object other) {
			return other != null && getClass() == other.getClass();
		}

		@Override
		public int hashCode() {
			return getClass().hashCode();
		}
	}
}
