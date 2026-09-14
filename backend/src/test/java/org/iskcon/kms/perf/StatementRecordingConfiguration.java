package org.iskcon.kms.perf;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Puts {@link StatementRecorder} in front of the application's {@code DataSource}, for the classes
 * that count the SQL a request sends (T-139, T-140, T-097).
 *
 * <p><strong>Why it is not in the suite's shared configuration.</strong> The recorder is off until a
 * test calls {@link StatementRecorder#start()}, but the wrapper is not: every connection the
 * application checks out comes back through a proxy, recording or not. That is harmless for the few
 * classes that want it and it is not something to impose on the hundred that do not, so these classes
 * import it and, by importing it, share one context among themselves instead of one with everybody.
 *
 * <p>Until T-189 this lived in {@code PerfStubVerifierConfiguration} beside a stub verifier of the
 * perf package's own, and {@code ShoppingListIT} declared a second copy of it. The verifier is now the
 * suite's shared one, and the copy is this class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StatementRecordingConfiguration {

	/**
	 * Declared {@code static} because a {@code BeanPostProcessor} is created before the configuration
	 * class that declares it can be fully initialised; see {@link StatementRecorder#wrapTheDataSource()}.
	 */
	@Bean
	static BeanPostProcessor statementRecorder() {
		return StatementRecorder.wrapTheDataSource();
	}
}
