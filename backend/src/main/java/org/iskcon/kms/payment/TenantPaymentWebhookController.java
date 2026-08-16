package org.iskcon.kms.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment webhooks addressed to one temple (E7-S9).
 *
 * <p>The token in the path is how a temple is identified, and it has to be, because the signature
 * can only be checked with that temple's own secret and we cannot know whose secret to fetch until
 * the temple is known. So the order is: read the token, find the temple, fetch its secret, verify
 * the raw bytes, and only then look at what the body says. The token is an identifier and not a
 * credential — the signature remains the whole of the trust, and a correct token with a bad
 * signature is refused exactly as an unknown token is.
 *
 * <p>The response is 200 for anything signed, including events we do not model, because a provider
 * escalates retries against any non-2xx. A bad signature is the one exception.
 */
@RestController
@RequestMapping("/api/v1/public/webhooks/payments")
public class TenantPaymentWebhookController {

	private static final Logger log = LoggerFactory.getLogger(TenantPaymentWebhookController.class);

	private final TenantPaymentSettingsService settings;
	private final List<PaymentWebhookSignature> signatures;
	private final PaymentWebhookService webhookService;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	public TenantPaymentWebhookController(TenantPaymentSettingsService settings,
			List<PaymentWebhookSignature> signatures, PaymentWebhookService webhookService,
			ObjectMapper objectMapper, MeterRegistry meterRegistry) {
		this.settings = settings;
		this.signatures = signatures;
		this.webhookService = webhookService;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
	}

	@PostMapping("/{token}")
	public ResponseEntity<Void> receive(
			@PathVariable String token,
			@RequestBody byte[] rawBody,
			HttpServletRequest request) {

		Optional<TenantPaymentSettingsService.WebhookAddressee> addressee =
				settings.tenantForWebhookToken(token);
		if (addressee.isEmpty()) {
			// Says nothing about whether the token is merely unknown or the temple has gone: an
			// answer that distinguishes them is an oracle for guessing tokens.
			log.warn("Payment webhook for an unknown token");
			meterRegistry.counter("kms.payments.webhook.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		UUID tenantId = addressee.get().tenantId();
		Optional<String> secret = settings.webhookSecretOf(tenantId);
		Optional<PaymentWebhookSignature> scheme = signatures.stream()
				.filter(s -> s.provider().equalsIgnoreCase(addressee.get().provider()))
				.findFirst();
		if (secret.isEmpty() || scheme.isEmpty()) {
			log.error("Temple {} has a webhook address but no secret or no scheme to verify with",
					tenantId);
			meterRegistry.counter("kms.payments.webhook.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		String signature = request.getHeader(scheme.get().header());
		if (!scheme.get().isValid(rawBody, signature, secret.get())) {
			log.warn("Rejected a payment webhook for temple {} with a missing or invalid signature",
					tenantId);
			meterRegistry.counter("kms.payments.webhook.rejected").increment();
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		// Signed, so this temple's provider is genuinely reaching us — the amber light on the
		// Settings screen turns green on the strength of this and nothing else.
		settings.markWebhookSeen(tenantId);

		String eventId = request.getHeader("X-Razorpay-Event-Id");
		String eventType = eventTypeOf(rawBody);
		if (eventId == null || eventType == null) {
			log.warn("Signed payment webhook for temple {} missing event id or type", tenantId);
			return ResponseEntity.ok().build();
		}
		webhookService.record(eventId, eventType, rawBody);
		return ResponseEntity.ok().build();
	}

	private String eventTypeOf(byte[] rawBody) {
		try {
			JsonNode event = objectMapper.readTree(rawBody).get("event");
			return event == null ? null : event.asText();
		} catch (Exception e) {
			return null;
		}
	}
}
