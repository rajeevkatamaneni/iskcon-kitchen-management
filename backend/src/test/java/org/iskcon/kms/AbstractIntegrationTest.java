package org.iskcon.kms;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL.
 *
 * <p>SYSTEM_DESIGN.md requires tenant isolation to be verified against real database behaviour
 * rather than mocked away — Row-Level Security is a database feature, and only a database can
 * demonstrate it. Every test that touches data extends this.
 *
 * <p>The container is a JVM-wide singleton started in a static initialiser, deliberately not
 * managed by {@code @Testcontainers}/{@code @Container}. That annotation pair stops the
 * container when a test class finishes, but Spring caches application contexts across classes
 * — so the second test class would inherit a cached context pointing at a container that had
 * already been shut down. Starting once and letting Ryuk reap it at JVM exit avoids that, and
 * is faster besides: one container for the whole suite instead of one per class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("kms_test")
					.withUsername("kms")
					.withPassword("kms");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void registerPostgresProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		// Schema comes from Flyway migrations, exactly as in production. Testing against a
		// Hibernate-generated schema would prove nothing about the RLS policies, since those
		// live in the migrations themselves.
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add("spring.flyway.enabled", () -> "true");
	}
}
