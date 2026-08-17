package org.iskcon.kms.notification;

import jakarta.validation.Valid;
import java.util.Collections;
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
 * A temple's WhatsApp connection (E1, E5), behind {@code MANAGE_TEMPLE_SETTINGS} — the same
 * permission and the same shape as the payment gateway beside it, because to an administrator these
 * are the same kind of task: connect an account this temple owns, prove it works, and be told what
 * to paste where.
 *
 * <p>Nothing here returns the access token or the app secret. The one secret it will hand back is
 * the verify token, which a temple cannot configure their callback without, and that reveal is
 * written to the audit log.
 */
@RestController
@RequestMapping("/api/v1/settings/whatsapp")
public class WhatsAppSettingsController {

	private final TenantWhatsAppSettingsService settings;
	private final TenantEmailIdentityService emails;

	public WhatsAppSettingsController(
			TenantWhatsAppSettingsService settings, TenantEmailIdentityService emails) {
		this.settings = settings;
		this.emails = emails;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantWhatsAppSettings read() {
		return settings.read();
	}

	@PutMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantWhatsAppSettings save(
			@Valid @RequestBody SaveWhatsAppSettingsRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return settings.save(actor, request.phoneNumberId(), request.wabaId(),
				request.accessToken(), request.appSecret());
	}

	/** Proves the stored credentials still reach Meta. Says nothing about whether callbacks arrive. */
	@PostMapping("/test")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantWhatsAppSettings test() {
		return settings.test();
	}

	/**
	 * The temple's own address, used as Reply-To on every email we send for it.
	 *
	 * <p>On this controller rather than one of its own because to an administrator it is one subject:
	 * how this temple reaches its people. Sending is never from this address — the platform's domain
	 * carries the records that keep mail out of a spam folder — so what is being set here is only
	 * where a reply lands.
	 */
	@GetMapping("/contact-email")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public Map<String, String> readContactEmail() {
		return Collections.singletonMap("contactEmail", emails.readContactEmail());
	}

	@PutMapping("/contact-email")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public Map<String, String> saveContactEmail(
			@RequestBody Map<String, String> body,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return Collections.singletonMap(
				"contactEmail", emails.saveContactEmail(actor, body.get("contactEmail")));
	}

	/** The verify token, to paste into Meta's callback setup. Audited on every read. */
	@PostMapping("/verify-token")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Map<String, String>> revealVerifyToken(
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.ok(
				Collections.singletonMap("verifyToken", settings.revealVerifyToken(actor)));
	}
}
