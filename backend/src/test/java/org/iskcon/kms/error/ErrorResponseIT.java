package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
		ResponseEntity<String> response = get("/api/v1/public/webhooks/test-errors/known");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).contains("KMS-400029");
		assertThat(response.getBody()).contains("We couldn't find that temple.");
		assertThat(response.getBody()).contains("Check the address and try again.");
	}

	@Test
	@DisplayName("an unexpected failure leaks nothing about the internals")
	void unexpectedFailureLeaksNothing() {
		ResponseEntity<String> response = get("/api/v1/public/webhooks/test-errors/unexpected");
		String body = response.getBody();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(body).contains("KMS-500001");

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
	@DisplayName("a rollback nobody has a sentence for is still an incident, not a communication failure")
	void anUnexplainedRollbackStillAnswersUnexpectedFailure() {
		// The negative half of T-100. That task gave the communication retry a real code for the
		// rollback its own design produces, and the tempting way to do it was an
		// @ExceptionHandler(UnexpectedRollbackException.class) in GlobalExceptionHandler. That
		// exception says nothing about which transaction rolled back, so such a handler would answer
		// "We couldn't send those copies just now" to a failed stock adjustment — confidently wrong,
		// which is worse than unhelpful. The retry catches its own commit instead; this endpoint has
		// nothing to do with communications, and must still be told KMS-500001.
		ResponseEntity<String> response = get("/api/v1/public/webhooks/test-errors/rollback");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).contains("KMS-500001");
		assertThat(response.getBody())
				.as("the catch-all was not widened into a communication failure")
				.doesNotContain("send those copies")
				.doesNotContain(ErrorCode.COMMUNICATION_RETRY_FAILED.reference());
	}

	@Test
	@DisplayName("a value the body cannot hold is answered by naming the field and its values")
	void anUnreadableBodyNamesTheFieldOverRealHttp() {
		// T-105, end to end and over the wire, because everything else about it is tested against
		// the handler directly: this is what proves Spring routes an unreadable body to
		// handleUnreadableBody at all, and that what it decided survives serialisation.
		//
		// "EACH" is the reported case verbatim — an ordinary typo for a unit, on a line of an
		// order. It used to be answered KMS-500001 and an HTTP 500; then KMS-400001 with an empty
		// field list, which is the same answer a failed constraint gets but with the useful half
		// removed.
		ResponseEntity<String> response = post(
				"/api/v1/public/webhooks/test-errors/unreadable", "{\"unit\":\"EACH\"}");
		String body = response.getBody();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(body).contains("KMS-400001");
		assertThat(body)
				.as("the field the caller got wrong, and the words it will accept")
				.contains("\"field\":\"unit\"")
				.contains("Choose one of KG, GM, L, ML, PIECES.");

		assertThat(body)
				.as("naming the field is the fix; pasting Jackson's sentence is the trap it "
						+ "must not be confused with")
				.doesNotContain("deserialize")
				.doesNotContain("com.fasterxml")
				.doesNotContain("org.iskcon")
				.doesNotContain("Exception");
	}

	@Test
	@DisplayName("a misspelt filter value is answered as a bad field, not as a missing resource")
	void aMisspeltFilterValueIsAFourHundredOverRealHttp() {
		// The half of MethodArgumentTypeMismatchException that was answered wrongly. The collection
		// is real and only the word is not, so "We couldn't find what you were looking for" would
		// send the reader hunting for something that exists.
		ResponseEntity<String> response = get("/api/v1/public/webhooks/test-errors/filter?unit=SNET");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody())
				.contains("KMS-400001")
				.contains("\"field\":\"unit\"")
				.contains("Choose one of KG, GM, L, ML, PIECES.");
	}

	@Test
	@DisplayName("an address that names no resource is still a 404, and now says which segment")
	void anUnusableAddressSegmentIsStillAFourOhFourOverRealHttp() {
		// The other half, and the half that was already right: nothing exists at this address and
		// nothing could. The status is unchanged; only the field naming is new.
		ResponseEntity<String> response = get("/api/v1/public/webhooks/test-errors/by-unit/SNET");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody())
				.contains("KMS-400030")
				.contains("\"field\":\"unit\"")
				.contains("Choose one of KG, GM, L, ML, PIECES.");
	}

	@Test
	@DisplayName("the code is always present, whatever failed")
	void everyFailureCarriesACode() {
		// Without this, a user's screenshot is undiagnosable — which is the entire reason the
		// scheme exists.
		assertThat(get("/api/v1/public/webhooks/test-errors/known").getBody()).contains("KMS-");
		assertThat(get("/api/v1/public/webhooks/test-errors/unexpected").getBody()).contains("KMS-");
	}

	private ResponseEntity<String> get(String path) {
		return rest.getForEntity("http://localhost:" + port + path, String.class);
	}

	private ResponseEntity<String> post(String path, String json) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return rest.postForEntity(
				"http://localhost:" + port + path, new HttpEntity<>(json, headers), String.class);
	}

	/**
	 * Mounted under the webhook prefix, which is not where it belongs and is the only place left.
	 *
	 * <p>These endpoints have to be reachable without a token, because what they prove is what an
	 * unauthenticated caller is shown when something fails. Until 2026-08-29 that was free: the
	 * whole of {@code /api/v1/public/**} was permitted by one wildcard. Narrowing that to three
	 * named prefixes — the point of which was that nothing should be public by accident of its path
	 * — took this with it. Of the three, the webhook prefix is the honest one: a provider calling a
	 * webhook is precisely an unauthenticated caller who must never be shown the inside of the
	 * machine.
	 */
	@RestController
	@RequestMapping("/api/v1/public/webhooks/test-errors")
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

		/**
		 * A rollback from somewhere that has no sentence of its own for it (T-100).
		 *
		 * <p>Thrown directly rather than provoked by a real transaction, deliberately: what is being
		 * proved is a property of {@code GlobalExceptionHandler}'s mapping, not of any transaction
		 * manager, and the exception a transaction manager would raise is this one.
		 */
		/**
		 * A body with a unit in it, so that a unit that is not one can be posted at it (T-105).
		 *
		 * <p>The method body is never reached and that is the point: Jackson refuses before any
		 * controller runs, which is exactly why an unreadable body skips Bean Validation and why it
		 * needed a field error of its own.
		 */
		@PostMapping("/unreadable")
		String unreadable(@RequestBody Line line) {
			return line.unit().name();
		}

		/** The product's own {@link Unit}, so this stops agreeing with itself if that list changes. */
		record Line(Unit unit) {
		}

		/**
		 * A unit as a filter, and the same unit as part of the address (T-105).
		 *
		 * <p>Two endpoints rather than one because the whole of the split is which of these a value
		 * came from: {@code ?unit=SNET} names a real collection and a wrong word, and
		 * {@code /by-unit/SNET} names nothing at all.
		 */
		@GetMapping("/filter")
		String filter(@RequestParam Unit unit) {
			return unit.name();
		}

		@GetMapping("/by-unit/{unit}")
		String byUnit(@PathVariable Unit unit) {
			return unit.name();
		}

		@GetMapping("/rollback")
		String rollback() {
			throw new UnexpectedRollbackException(
					"Transaction silently rolled back because it has been marked as rollback-only");
		}
	}

	@TestConfiguration
	static class Config {
	}
}
