package org.iskcon.kms.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Razorpay webhook infrastructure (E7-S9): signature gating, dedup, dispatch, dead-lettering, and
 * replay — exercised with a controllable test handler so the donation stories can rely on it.
 */
@AutoConfigureMockMvc
@Import(PaymentWebhookIT.TestHandlerConfiguration.class)
class PaymentWebhookIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PaymentWebhookVerifier verifier;

	@Autowired
	private PaymentWebhookService webhookService;

	@Autowired
	private TestPaymentHandler handler;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		handler.handled.set(0);
		handler.failNext = false;
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM payment_events");
	}

	@Test
	@DisplayName("a wrongly-signed webhook is rejected")
	void badSignatureRejected() throws Exception {
		byte[] body = body("test.event");
		mvc.perform(post("/api/v1/public/webhooks/razorpay")
						.header("X-Razorpay-Signature", "deadbeef")
						.header("X-Razorpay-Event-Id", "evt-bad")
						.content(body))
				.andExpect(status().isForbidden());
		assert count() == 0 : "nothing should be stored for a bad signature";
	}

	@Test
	@DisplayName("an unknown event type is acknowledged and ignored, never a 500")
	void unknownTypeIgnored() throws Exception {
		deliver("payment.some_future_type", "evt-unknown");
		assert eventStatus("evt-unknown").equals("IGNORED") : "unknown type should be ignored";
		assert handler.handled.get() == 0;
	}

	@Test
	@DisplayName("a known event is dispatched to its handler and marked processed")
	void knownEventProcessed() throws Exception {
		deliver("test.event", "evt-1");
		assert handler.handled.get() == 1 : "handler should run once";
		assert eventStatus("evt-1").equals("PROCESSED");
	}

	@Test
	@DisplayName("a duplicate delivery is deduped — the handler runs once")
	void duplicateDeduped() throws Exception {
		deliver("test.event", "evt-dup");
		deliver("test.event", "evt-dup");
		assert handler.handled.get() == 1 : "a replayed event id must not re-run the handler";
		Integer rows = admin.queryForObject(
				"SELECT count(*) FROM payment_events WHERE provider_event_id = 'evt-dup'", Integer.class);
		assert rows == 1 : "one stored row per event id";
	}

	@Test
	@DisplayName("a handler failure dead-letters the event, and replay after the fix processes it")
	void deadLetterAndReplay() throws Exception {
		handler.failNext = true;
		deliver("test.fail", "evt-dl");
		assert eventStatus("evt-dl").equals("DEAD_LETTER") : "a failing handler should dead-letter";
		assert webhookService.deadLetters().stream().anyMatch(d -> d.providerEventId().equals("evt-dl"));

		// Fix the condition, then replay.
		handler.failNext = false;
		java.util.UUID id = admin.queryForObject(
				"SELECT id FROM payment_events WHERE provider_event_id = 'evt-dl'", java.util.UUID.class);
		PaymentWebhookService.Outcome outcome = webhookService.replay(id);
		assert outcome == PaymentWebhookService.Outcome.PROCESSED : "replay should process, was " + outcome;
		assert eventStatus("evt-dl").equals("PROCESSED");
	}

	// ---------------------------------------------------------------------

	private void deliver(String eventType, String eventId) throws Exception {
		byte[] body = body(eventType);
		mvc.perform(post("/api/v1/public/webhooks/razorpay")
						.header("X-Razorpay-Signature", verifier.sign(body))
						.header("X-Razorpay-Event-Id", eventId)
						.content(body))
				.andExpect(status().isOk());
	}

	private static byte[] body(String eventType) {
		return ("{\"event\":\"" + eventType + "\",\"payload\":{}}").getBytes(StandardCharsets.UTF_8);
	}

	private String eventStatus(String eventId) {
		return admin.queryForObject(
				"SELECT status FROM payment_events WHERE provider_event_id = ?", String.class, eventId);
	}

	private int count() {
		Integer n = admin.queryForObject("SELECT count(*) FROM payment_events", Integer.class);
		return n == null ? 0 : n;
	}

	// ---------------------------------------------------------------------

	static class TestPaymentHandler implements PaymentEventHandler {
		final AtomicInteger handled = new AtomicInteger();
		volatile boolean failNext = false;

		@Override
		public java.util.Set<String> subscribedEventTypes() {
			return java.util.Set.of("test.event", "test.fail");
		}

		@Override
		public void handle(PaymentEvent event) {
			if (event.eventType().equals("test.fail") && failNext) {
				throw new IllegalStateException("boom");
			}
			handled.incrementAndGet();
		}
	}

	@TestConfiguration
	static class TestHandlerConfiguration {
		@Bean
		TestPaymentHandler testPaymentHandler() {
			return new TestPaymentHandler();
		}
	}
}
