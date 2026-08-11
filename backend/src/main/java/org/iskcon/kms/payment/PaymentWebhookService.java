package org.iskcon.kms.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * The spine of payment webhook processing (E7-S9): dedup by the provider's event id, dispatch to the
 * handlers that claim the type, and park anything a handler can't process as a dead letter for replay.
 * Unknown types are acknowledged and ignored — never a 500, because Razorpay punishes that with
 * escalating retries.
 *
 * <p>Deliberately not wrapped in one transaction: each handler owns its own transaction (so a failing
 * one rolls back its own writes), and the event-store status updates are their own statements. That
 * keeps a handler failure from poisoning the record of what happened.
 */
@Service
public class PaymentWebhookService {

	/** What became of a delivered event. */
	public enum Outcome { PROCESSED, IGNORED, DUPLICATE, DEAD_LETTER, NOT_FOUND }

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final List<PaymentEventHandler> handlers;
	private final MeterRegistry meterRegistry;

	public PaymentWebhookService(JdbcTemplate jdbc, ObjectMapper objectMapper,
			List<PaymentEventHandler> handlers, MeterRegistry meterRegistry) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.handlers = handlers;
		this.meterRegistry = meterRegistry;
	}

	/** Stores a delivered event (deduped) and dispatches it. A replay of the same id is a no-op. */
	public Outcome record(String providerEventId, String eventType, byte[] rawPayload) {
		String json = new String(rawPayload, StandardCharsets.UTF_8);
		UUID id = UUID.randomUUID();
		List<UUID> inserted = jdbc.query("""
				INSERT INTO payment_events (id, provider_event_id, event_type, payload)
				VALUES (?, ?, ?, CAST(? AS jsonb))
				ON CONFLICT (provider, provider_event_id) DO NOTHING
				RETURNING id
				""", (rs, n) -> rs.getObject("id", UUID.class), id, providerEventId, eventType, json);
		if (inserted.isEmpty()) {
			meterRegistry.counter("kms.payments.webhook.duplicate").increment();
			return Outcome.DUPLICATE;
		}
		return dispatch(id, eventType, json);
	}

	/** Re-dispatches a dead-lettered event after the underlying fix (super-admin ops). */
	public Outcome replay(UUID id) {
		String eventType;
		String json;
		try {
			var row = jdbc.queryForMap(
					"SELECT event_type, payload::text AS payload FROM payment_events WHERE id = ? AND status = 'DEAD_LETTER'",
					id);
			eventType = (String) row.get("event_type");
			json = (String) row.get("payload");
		} catch (EmptyResultDataAccessException e) {
			return Outcome.NOT_FOUND;
		}
		return dispatch(id, eventType, json);
	}

	public List<DeadLetterView> deadLetters() {
		return jdbc.query("""
				SELECT id, provider_event_id, event_type, error, attempts, received_at
				FROM payment_events WHERE status = 'DEAD_LETTER' ORDER BY received_at
				""", (rs, n) -> new DeadLetterView(
				rs.getObject("id", UUID.class), rs.getString("provider_event_id"),
				rs.getString("event_type"), rs.getString("error"), rs.getInt("attempts"),
				rs.getObject("received_at", OffsetDateTime.class).toInstant()));
	}

	// ---------------------------------------------------------------------

	private Outcome dispatch(UUID id, String eventType, String json) {
		List<PaymentEventHandler> matching = handlers.stream().filter(h -> h.handles(eventType)).toList();
		if (matching.isEmpty()) {
			jdbc.update("UPDATE payment_events SET status = 'IGNORED', processed_at = now() WHERE id = ?", id);
			log.info("Payment event {} of type {} ignored — no handler", id, eventType);
			return Outcome.IGNORED;
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			PaymentEvent event = new PaymentEvent(id, eventType, node);
			for (PaymentEventHandler handler : matching) {
				handler.handle(event);
			}
			jdbc.update("""
					UPDATE payment_events SET status = 'PROCESSED', processed_at = now(),
						attempts = attempts + 1, error = NULL WHERE id = ?
					""", id);
			return Outcome.PROCESSED;
		} catch (Exception e) {
			meterRegistry.counter("kms.payments.webhook.dead_letter").increment();
			jdbc.update("UPDATE payment_events SET status = 'DEAD_LETTER', attempts = attempts + 1, error = ? WHERE id = ?",
					trim(e.getMessage()), id);
			log.error("Payment event {} dead-lettered", id, e);
			return Outcome.DEAD_LETTER;
		}
	}

	private static String trim(String message) {
		if (message == null) {
			return "processing failed";
		}
		return message.length() > 1000 ? message.substring(0, 1000) : message;
	}
}
