package org.iskcon.kms.payment;

import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A temple's own settings (E7): today, how it collects donations.
 *
 * <p>Everything here is {@code MANAGE_TEMPLE_SETTINGS} — the permission a Temple Admin already holds
 * — because these are the keys to the temple's money. Nothing on this controller ever returns the
 * payment key secret; the one secret it will hand back is the webhook secret, which a temple cannot
 * configure their provider without, and that reveal is written to the audit log.
 */
@RestController
@RequestMapping("/api/v1/settings")
public class TenantSettingsController {

	private final TenantPaymentSettingsService settings;
	private final List<PaymentProviderProbe> probes;

	public TenantSettingsController(TenantPaymentSettingsService settings,
			List<PaymentProviderProbe> probes) {
		this.settings = settings;
		this.probes = probes;
	}

	/** What the Settings screen draws. */
	@GetMapping("/payments")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantPaymentSettings payments() {
		return settings.read();
	}

	/**
	 * The providers this application has actually been built to talk to.
	 *
	 * <p>Served rather than hardcoded in the screen so the list can never promise a temple a provider
	 * whose adapter does not exist — the dropdown is exactly the set of probes on the classpath.
	 */
	@GetMapping("/payments/providers")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public List<Map<String, String>> providers() {
		return probes.stream()
				.map(p -> Map.of("value", p.provider(), "label", label(p.provider())))
				.toList();
	}

	/**
	 * The events a temple must subscribe to in its provider's dashboard, grouped by what they are for.
	 *
	 * <p>Served rather than written into the screen, for the same reason the provider list is: the
	 * only correct answer is the set the running application actually acts on, and a copy typed into
	 * a page drifts from it silently — as it already had, omitting every subscription event.
	 */
	@GetMapping("/payments/events")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public List<WebhookSubscription> events() {
		return settings.subscriptions();
	}

	@PutMapping("/payments")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantPaymentSettings save(
			@Valid @RequestBody SavePaymentSettingsRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return settings.save(actor, request.provider(), request.keyId(), request.keySecret());
	}

	/** Proves the stored credentials still reach the provider. Says nothing about webhooks. */
	@PostMapping("/payments/test")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantPaymentSettings test() {
		return settings.test();
	}

	/** The webhook secret, to paste into the provider's dashboard. Audited on every read. */
	@PostMapping("/payments/webhook-secret")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Map<String, String>> revealWebhookSecret(
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.ok(
				Collections.singletonMap("webhookSecret", settings.revealWebhookSecret(actor)));
	}

	private static String label(String provider) {
		return switch (provider) {
			case "RAZORPAY" -> "Razorpay — India";
			case "STRIPE" -> "Stripe — outside India";
			default -> provider;
		};
	}
}
