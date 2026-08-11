package org.iskcon.kms.payment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Super-Admin ops surface for payment webhooks (E7-S9): the dead-letter queue and a
 * replay-after-fix action. Behind {@code VIEW_PLATFORM_OPERATIONS} — payment events are
 * platform-level infrastructure, not a temple's business data.
 */
@RestController
@RequestMapping("/api/v1/ops/payment-events")
public class PaymentOpsController {

	private final PaymentWebhookService webhookService;

	public PaymentOpsController(PaymentWebhookService webhookService) {
		this.webhookService = webhookService;
	}

	@GetMapping("/dead-letter")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public List<DeadLetterView> deadLetters() {
		return webhookService.deadLetters();
	}

	@PostMapping("/{id}/replay")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public ResponseEntity<Map<String, Object>> replay(@PathVariable UUID id) {
		PaymentWebhookService.Outcome outcome = webhookService.replay(id);
		return ResponseEntity.ok(Map.of("outcome", outcome.name()));
	}
}
