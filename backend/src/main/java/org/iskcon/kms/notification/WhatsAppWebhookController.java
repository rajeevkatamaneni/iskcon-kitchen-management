package org.iskcon.kms.notification;

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
 * Receives delivery-status callbacks from the WhatsApp provider. Public by necessity — the provider
 * has no account here — so the signature is the whole of the trust: an unsigned or wrongly-signed
 * body is refused before anything is read from it.
 *
 * <p>The body is taken as raw bytes because the signature is over the exact bytes received; parsing
 * happens only after the signature checks out. The payload shape here is the simple {@code
 * {messageId, status}} contract the dev pipeline uses; adapting Meta's richer nested payload to it is
 * part of wiring the real provider (the Meta setup checklist), not this endpoint's logic.
 */
@RestController
@RequestMapping("/api/v1/public/webhooks")
public class WhatsAppWebhookController {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

	private final WebhookSignatureVerifier verifier;
	private final NotificationDeliveryService deliveryService;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	public WhatsAppWebhookController(
			WebhookSignatureVerifier verifier,
			NotificationDeliveryService deliveryService,
			ObjectMapper objectMapper,
			MeterRegistry meterRegistry) {
		this.verifier = verifier;
		this.deliveryService = deliveryService;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
	}

	@PostMapping("/whatsapp")
	public ResponseEntity<Void> receive(
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
			@RequestBody byte[] rawBody) {

		if (!verifier.isValid(rawBody, signature)) {
			log.warn("Rejected a delivery webhook with a missing or invalid signature");
			meterRegistry.counter("kms.webhooks.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		DeliveryWebhookPayload payload = parse(rawBody);
		if (payload == null) {
			// Signed but unreadable — acknowledge so the provider does not hammer us with retries,
			// but there is nothing to apply.
			return ResponseEntity.ok().build();
		}

		deliveryService.applyStatus(payload.messageId(), DeliveryStatus.from(payload.status()));
		return ResponseEntity.ok().build();
	}

	private DeliveryWebhookPayload parse(byte[] rawBody) {
		try {
			return objectMapper.readValue(rawBody, DeliveryWebhookPayload.class);
		} catch (Exception e) {
			log.warn("Delivery webhook body could not be parsed", e);
			return null;
		}
	}

	/** The delivery callback shape: which message, and what happened to it. */
	private record DeliveryWebhookPayload(String messageId, String status) {
	}
}
