package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the contract over real HTTP: users get plain language and a code, and never anything
 * from inside the machine.
 *
 * <p>The leak test matters most. It is easy to add a helpful-looking exception message during
 * debugging and ship it, and the result is a temple administrator reading a Java class name.
 */
@Import(ErrorResponseIT.ThrowingEndpoints.class)
class ErrorResponseIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("an anticipated failure returns its code, message and next step")
	void anticipatedFailureIsWellFormed() {
		ResponseEntity<String> response = get("/api/v1/public/test-errors/known");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).contains("KMS-4401");
		assertThat(response.getBody()).contains("We couldn't find that temple.");
		assertThat(response.getBody()).contains("Check the address and try again.");
	}

	@Test
	@DisplayName("an unexpected failure leaks nothing about the internals")
	void unexpectedFailureLeaksNothing() {
		ResponseEntity<String> response = get("/api/v1/public/test-errors/unexpected");
		String body = response.getBody();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(body).contains("KMS-5001");

		// The thrown exception deliberately carries a message a careless handler would echo.
		assertThat(body)
				.as("the response must not repeat the internal exception message")
				.doesNotContain("jdbc")
				.doesNotContain("NullPointer")
				.doesNotContain("IllegalState")
				.doesNotContain("org.iskcon")
				.doesNotContain("java.lang")
				.doesNotContain("at org.")
				.doesNotContain("secret-connection-string");
	}

	@Test
	@DisplayName("the code is always present, whatever failed")
	void everyFailureCarriesACode() {
		// Without this, a user's screenshot is undiagnosable — which is the entire reason the
		// scheme exists.
		assertThat(get("/api/v1/public/test-errors/known").getBody()).contains("KMS-");
		assertThat(get("/api/v1/public/test-errors/unexpected").getBody()).contains("KMS-");
	}

	private ResponseEntity<String> get(String path) {
		return rest.getForEntity("http://localhost:" + port + path, String.class);
	}

	@RestController
	@RequestMapping("/api/v1/public/test-errors")
	static class ThrowingEndpoints {

		@GetMapping("/known")
		String known() {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND);
		}

		@GetMapping("/unexpected")
		String unexpected() {
			// Deliberately the kind of message that must never reach a screen.
			throw new IllegalStateException(
					"jdbc connection failed for secret-connection-string at org.iskcon.kms");
		}
	}

	@TestConfiguration
	static class Config {
	}
}
