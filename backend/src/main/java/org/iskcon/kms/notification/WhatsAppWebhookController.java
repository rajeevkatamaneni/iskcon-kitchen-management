package org.iskcon.kms.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meta's WhatsApp callbacks, addressed to one temple (E1, E5).
 *
 * <p>The token in the path is how a temple is identified, and it has to be: Meta signs with that
 * temple's own app secret, and we cannot know whose secret to fetch until the temple is known. So
 * the order is: read the token, find the temple, fetch its secret, verify the raw bytes, and only
 * then look at what the body says. The token is an identifier and not a credential — the signature
 * remains the whole of the trust, and a correct token with a bad signature is refused exactly as an
 * unknown token is.
 *
 * <p>Two methods, because Meta uses both. It will not accept a callback URL until a GET to it echoes
 * back a challenge it sent, proving whoever configured the URL also holds the verify token. Then
 * delivery receipts arrive by POST, in Meta's own nested shape: an envelope of entries, each with
 * changes, each carrying a list of statuses. We answer 200 to anything correctly signed, including
 * events we do not model, because a provider escalates retries against any non-2xx.
 */
@RestController
@RequestMapping("/api/v1/public/webhooks/whatsapp")
public class WhatsAppWebhookController {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

	private final WebhookSignatureVerifier verifier;
	private final NotificationDeliveryService deliveryService;
	private final TenantWhatsAppSettingsService settings;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	public WhatsAppWebhookController(
			WebhookSignatureVerifier verifier,
			NotificationDeliveryService deliveryService,
			TenantWhatsAppSettingsService settings,
			ObjectMapper objectMapper,
			MeterRegistry meterRegistry) {
		this.verifier = verifier;
		this.deliveryService = deliveryService;
		this.settings = settings;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Meta's subscription handshake: echo the challenge, but only to someone who knows this temple's
	 * verify token. Answering it unconditionally would let anyone who guessed the URL complete
	 * somebody else's callback setup.
	 */
	@GetMapping("/{token}")
	public ResponseEntity<String> verify(
			@PathVariable String token,
			@RequestParam(value = "hub.mode", required = false) String mode,
			@RequestParam(value = "hub.verify_token", required = false) String verifyToken,
			@RequestParam(value = "hub.challenge", required = false) String challenge) {

		Optional<UUID> tenantId = settings.tenantForWebhookToken(token)
				.map(TenantWhatsAppSettingsService.CallbackAddressee::tenantId);
		if (tenantId.isEmpty() || !"subscribe".equals(mode)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Optional<String> expected = settings.verifyTokenOf(tenantId.get());
		if (expected.isEmpty() || verifyToken == null || !expected.get().equals(verifyToken)) {
			log.warn("Rejected a WhatsApp callback handshake with the wrong verify token");
			meterRegistry.counter("kms.webhooks.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		// Meta wants the challenge back as a bare body, not as JSON.
		return ResponseEntity.ok(challenge == null ? "" : challenge);
	}

	@PostMapping("/{token}")
	public ResponseEntity<Void> receive(
			@PathVariable String token,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
			@RequestBody byte[] rawBody) {

		Optional<UUID> tenantId = settings.tenantForWebhookToken(token)
				.map(TenantWhatsAppSettingsService.CallbackAddressee::tenantId);
		if (tenantId.isEmpty()) {
			// Says nothing about whether the token is merely unknown or the temple has gone: an
			// answer that distinguishes them is an oracle for guessing tokens.
			log.warn("WhatsApp callback for an unknown token");
			meterRegistry.counter("kms.webhooks.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		Optional<String> appSecret = settings.appSecretOf(tenantId.get());
		if (appSecret.isEmpty() || !verifier.isValid(rawBody, signature, appSecret.get())) {
			log.warn("Rejected a WhatsApp callback for temple {} with a missing or invalid signature",
					tenantId.get());
			meterRegistry.counter("kms.webhooks.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		// Signed, so this temple's Meta app is genuinely reaching us — the amber light on the
		// Settings screen turns green on the strength of this and nothing else.
		settings.markWebhookSeen(tenantId.get());

		applyStatuses(rawBody);
		return ResponseEntity.ok().build();
	}

	/**
	 * Walks Meta's envelope and applies every delivery receipt in it.
	 *
	 * <p>Signed but unreadable is acknowledged rather than refused: a 4xx only makes Meta send it
	 * again, and a body we cannot parse will not parse on the second attempt either.
	 */
	private void applyStatuses(byte[] rawBody) {
		try {
			JsonNode root = objectMapper.readTree(rawBody);
			for (JsonNode entry : root.path("entry")) {
				for (JsonNode change : entry.path("changes")) {
					for (JsonNode status : change.path("value").path("statuses")) {
						String messageId = text(status, "id");
						if (messageId != null) {
							deliveryService.applyStatus(messageId, DeliveryStatus.from(text(status, "status")));
						}
					}
				}
			}
		} catch (Exception e) {
			log.warn("WhatsApp callback body could not be read", e);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}
}
