package org.iskcon.kms.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Razorpay payment webhooks (E7-S9). Public by necessity — the provider has no account here
 * — so the signature is the whole of the trust: the raw body is verified before it is parsed. The
 * body is taken as raw bytes because the signature is over the exact bytes received. A verified event
 * is stored, deduped and dispatched; the response is always 200 (except a bad signature), because
 * Razorpay escalates retries against any non-2xx, including on events we don't model.
 */
@RestController
@RequestMapping("/api/v1/public/webhooks")
public class PaymentWebhookController {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

	private final PaymentWebhookVerifier verifier;
	private final PaymentWebhookService webhookService;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	public PaymentWebhookController(PaymentWebhookVerifier verifier, PaymentWebhookService webhookService,
			ObjectMapper objectMapper, MeterRegistry meterRegistry) {
		this.verifier = verifier;
		this.webhookService = webhookService;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
	}

	@PostMapping("/razorpay")
	public ResponseEntity<Void> receive(
			@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
			@RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
			@RequestBody byte[] rawBody) {

		if (!verifier.isValid(rawBody, signature)) {
			log.warn("Rejected a payment webhook with a missing or invalid signature");
			meterRegistry.counter("kms.payments.webhook.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		String eventType = eventTypeOf(rawBody);
		if (eventId == null || eventType == null) {
			// Signed but unusable (no id or type). Acknowledge so retries stop; nothing to process.
			log.warn("Signed payment webhook missing event id or type");
			return ResponseEntity.ok().build();
		}
		webhookService.record(eventId, eventType, rawBody);
		return ResponseEntity.ok().build();
	}

	private String eventTypeOf(byte[] rawBody) {
		try {
			JsonNode node = objectMapper.readTree(rawBody);
			JsonNode event = node.get("event");
			return event == null ? null : event.asText();
		} catch (Exception e) {
			return null;
		}
	}
}
