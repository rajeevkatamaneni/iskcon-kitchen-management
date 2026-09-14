package org.iskcon.kms.notification;

import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Map;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Autowired;
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
	private final WhatsAppTemplateComparison comparison;

	@Autowired
	public WhatsAppSettingsController(TenantWhatsAppSettingsService settings,
			TenantEmailIdentityService emails, WhatsAppTemplateComparison comparison) {
		this.settings = settings;
		this.emails = emails;
		this.comparison = comparison;
	}

	/**
	 * For the integration tests written before the comparison existed (T-173), which build this
	 * controller by hand with two arguments and never call {@link #compareTemplatesWithMeta}.
	 * Package-private, so nothing outside this package can build a controller without the comparison,
	 * and Spring uses the constructor above. Those tests are other tasks' files; once they pass a
	 * comparison, this constructor can go.
	 */
	WhatsAppSettingsController(TenantWhatsAppSettingsService settings, TenantEmailIdentityService emails) {
		this(settings, emails, null);
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
		return settings.save(actor, request.phoneNumberId(), request.wabaId(), request.appId(),
				request.accessToken(), request.appSecret());
	}

	/**
	 * Sends Meta every template that is waiting: new ones, changed wording, and ones Meta did not
	 * register last time (T-169a). Answers with the settings as they stand after it, the same view as
	 * the GET, so the button can show what is still waiting without a second request.
	 *
	 * <p>Rajeev, 2026-09-13: <em>"For Watts App specifically, we need a new button Reload Wattsapp
	 * Templates."</em> After a temple's first connection this is the only way templates reach Meta;
	 * Save only saves. Same permission as everything else on this controller, because sending
	 * templates to a temple's Meta account is part of connecting that account.
	 *
	 * <p>Synchronous, as Save's submission was: one to three Meta calls per template, inside the
	 * request. See the service for why that is recorded as a limit rather than built around.
	 */
	@PostMapping("/templates/reload")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantWhatsAppSettings reloadTemplates(@AuthenticationPrincipal AuthenticatedUser actor) {
		return settings.reloadTemplates(actor);
	}

	/**
	 * What Meta holds for each template beside what this release would send, read and never written
	 * (T-173).
	 *
	 * <p>Reload rewords any template whose body Meta holds differently from ours, and Meta allows one
	 * edit a day. Whether Meta returns a body exactly as it was registered had not been confirmed, and a
	 * Meta that normalised the text would have every template reworded on the first Reload. This answers
	 * that by asking, with the same lookup Reload uses, and changes nothing: no POST to Meta, no write to
	 * {@code tenant_settings}. See {@link WhatsAppTemplateComparison}.
	 *
	 * <p>A GET, and behind the same permission as the rest of this controller, because it reads this
	 * temple's own Meta account with this temple's own token. No screen calls it; it is read by hand.
	 */
	@GetMapping("/templates/meta-comparison")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public WhatsAppTemplateComparison.Report compareTemplatesWithMeta() {
		return comparison.compare();
	}

	/**
	 * Sends a real test message from the temple's number to the phone typed (T-151), and answers with
	 * the settings as they stand after it.
	 *
	 * <p>Same path and permission as the credential check it replaced, so nothing about who may press
	 * the button changed — only what pressing it does. Says nothing about whether callbacks arrive;
	 * that is the second status line on the screen, and only Meta calling us can settle it.
	 */
	@PostMapping("/test")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public TenantWhatsAppSettings test(
			@Valid @RequestBody SendWhatsAppTestRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return settings.sendTestMessage(actor, request.phoneNumber());
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
