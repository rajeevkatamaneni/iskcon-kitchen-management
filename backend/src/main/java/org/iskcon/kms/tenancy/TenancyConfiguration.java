package org.iskcon.kms.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link TenantAwareDataSource} in front of the real connection pool.
 *
 * <p>Everything that touches the database — JPA, JdbcTemplate, Flyway — resolves the
 * {@code DataSource} bean, so wrapping it here means tenant scoping applies everywhere by
 * construction. There is no second path to the database that could bypass it, which is the
 * point: isolation must not depend on each caller remembering to opt in.
 *
 * <p>A {@link BeanPostProcessor} is used rather than declaring our own {@code DataSource} bean
 * so that Spring Boot still builds the pool itself, with all of its normal configuration
 * handling. Rebuilding it by hand meant re-implementing that correctly, and getting it wrong.
 */
@Configuration
public class TenancyConfiguration {

	@Bean
	public static BeanPostProcessor tenantAwareDataSourceWrapper() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
					return new TenantAwareDataSource(dataSource);
				}
				return bean;
			}
		};
	}
}
