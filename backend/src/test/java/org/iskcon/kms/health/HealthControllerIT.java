package org.iskcon.kms.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

/**
 * E1-S1 acceptance criterion: "gradlew test passes... backend connects to a real
 * (Testcontainers) Postgres." This is the whole-stack smoke test — HTTP layer up,
 * app context wired, database reachable — that every later story's CI run implicitly
 * re-verifies.
 */
class HealthControllerIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate testRestTemplate;

	@LocalServerPort
	private int port;

	@Test
	void healthEndpointReturnsUpWithTimestamp() {
		ResponseEntity<String> response =
				testRestTemplate.getForEntity("http://localhost:" + port + "/health", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getBody()).contains("\"status\":\"UP\"");
		assertThat(response.getBody()).contains("\"timestamp\"");
	}
}
