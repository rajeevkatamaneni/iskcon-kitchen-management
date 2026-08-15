package org.iskcon.kms.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Runs migrations as the schema-owning migration role rather than as the application role.
 *
 * <p>The two roles exist for the reason the whole schema exists: the application connects with no
 * DDL and no BYPASSRLS, so a bug cannot reshape the database or read another temple's rows. That is
 * only true if something else owns the schema — and until now nothing did on a real deployment,
 * because Flyway shared the application's DataSource. The consequence was not theoretical: the
 * application role owned every table it was supposed to be constrained by.
 *
 * <p>Spring's own {@code spring.flyway.user} cannot be used, because setting it makes Flyway derive
 * its DataSource from the primary one — which is wrapped by {@code TenantAwareDataSource} and
 * cannot accept credential overrides. Handing Flyway its own plain, unwrapped connection details
 * avoids that entirely.
 *
 * <p>Absent credentials, nothing changes: local development and the test suite keep running
 * migrations exactly as before.
 */
@Configuration
// An empty value is the local/test case: the property exists but names no separate role.
@ConditionalOnExpression("'${kms.db.migration.user:}' != ''")
public class FlywayMigrationRoleConfiguration {

	@Bean
	public FlywayConfigurationCustomizer migrationRoleCustomizer(
			@Value("${spring.datasource.url}") String url,
			@Value("${kms.db.migration.user}") String user,
			@Value("${kms.db.migration.password}") String password) {
		return (FluentConfiguration configuration) -> configuration.dataSource(url, user, password);
	}
}
