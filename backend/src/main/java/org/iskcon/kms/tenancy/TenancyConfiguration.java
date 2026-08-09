package org.iskcon.kms.tenancy;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link TenantAwareDataSource} in front of the real connection pool.
 *
 * <p>Every component that touches the database — JPA, JdbcTemplate, Flyway at runtime — resolves
 * the primary {@code DataSource}, so routing it through the wrapper here means tenant scoping is
 * applied everywhere by construction. There is no second path to the database that could bypass
 * it, which is the point: isolation should not depend on each caller opting in.
 */
@Configuration
public class TenancyConfiguration {

	/**
	 * Declaring a {@code DataSource} here causes Spring Boot's own auto-configuration to back
	 * off, so this is the only one in the context. {@code DataSourceProperties} is injected
	 * rather than redeclared — Boot already publishes it, and defining a second copy makes the
	 * context ambiguous and unresolvable.
	 */
	@Bean
	public DataSource dataSource(DataSourceProperties properties) {
		DataSource pooled = properties.initializeDataSourceBuilder().build();
		return new TenantAwareDataSource(pooled);
	}
}
