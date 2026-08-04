package org.iskcon.kms;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that need a real Postgres, per SYSTEM_DESIGN.md's
 * requirement that RLS and tenancy behaviour be verified against real database engine
 * behaviour, not mocked away (E1-S3 and onward will extend this heavily).
 *
 * <p>One shared container per test JVM (started once, reused across test classes) keeps
 * the suite fast as story count grows through Epic 1.
 */
@Testcontainers
@ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("kms_test")
					.withUsername("kms")
					.withPassword("kms");

	@DynamicPropertySource
	static void registerPostgresProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		// No schema exists yet in E1-S1 — ddl-auto is overridden to "none" here and will
		// switch to Flyway-managed migrations starting E1-S3 (tenant model + RLS).
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	protected org.springframework.web.client.RestTemplate restTemplate() {
		return new TestRestTemplate().getRestTemplate();
	}
}
