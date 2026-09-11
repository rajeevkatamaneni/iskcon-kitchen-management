package org.iskcon.kms.notification;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A temple's own WhatsApp Business account (E1, E5): holding the credentials, proving they work, and
 * registering the templates Meta insists on.
 *
 * <p>The same division as the payment gateway, deliberately: the database keeps the two Meta ids,
 * the opaque callback token and the dates; the access token, the app secret and the verify token go
 * to {@link TenantSecretStore}. A dump of this schema leaks nothing that can send a message in a
 * temple's name.
 */
@Service
public class TenantWhatsAppSettingsService {

	private static final Logger log = LoggerFactory.getLogger(TenantWhatsAppSettingsService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;
	private final TenantSecretStore secrets;
	private final AuditService auditService;
	private final MetaWhatsAppClient meta;
	private final String apiBaseUrl;

	public TenantWhatsAppSettingsService(JdbcTemplate jdbc, TenantSecretStore secrets,
			AuditService auditService, MetaWhatsAppClient meta,
			@Value("${kms.api-base-url:}") String apiBaseUrl) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.auditService = auditService;
		this.meta = meta;
		this.apiBaseUrl = apiBaseUrl;
	}

	/** What the Settings screen shows. Never includes a secret. */
	@Transactional(readOnly = true)
	public TenantWhatsAppSettings read() {
		Map<String, Object> row;
		try {
			row = jdbc.queryForMap("""
					SELECT whatsapp_phone_number_id, whatsapp_waba_id, whatsapp_webhook_token,
						   whatsapp_display_number, whatsapp_verified_at, whatsapp_webhook_seen_at,
						   whatsapp_templates_submitted_at
					FROM tenant_settings
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		} catch (EmptyResultDataAccessException noRowYet) {
			return TenantWhatsAppSettings.none();
		}
		String phoneNumberId = (String) row.get("whatsapp_phone_number_id");
		if (phoneNumberId == null) {
			return TenantWhatsAppSettings.none();
		}
		return new TenantWhatsAppSettings(
				true,
				phoneNumberId,
				(String) row.get("whatsapp_waba_id"),
				(String) row.get("whatsapp_display_number"),
				webhookUrl((String) row.get("whatsapp_webhook_token")),
				instant(row.get("whatsapp_verified_at")),
				instant(row.get("whatsapp_webhook_seen_at")),
				instant(row.get("whatsapp_templates_submitted_at")));
	}

	/**
	 * Connects this temple's WhatsApp account.
	 *
	 * <p>The credentials are proven against Meta before anything is written, as the payment keys are:
	 * a token that does not work is a mistake to correct now, not a silence to debug at the first
	 * shift reminder. The callback token and verify token are minted once and kept across later
	 * edits, because they are already in the temple's Meta dashboard and changing them silently would
	 * stop delivery receipts.
	 */
	@Transactional
	public TenantWhatsAppSettings save(AuthenticatedUser actor, String phoneNumberId, String wabaId,
			String accessToken, String appSecret) {

		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));

		String tokenToUse = keep(accessToken, tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN, "accessToken");
		String secretToUse = keep(appSecret, tenantId, TenantSecretStore.Kind.WHATSAPP_APP_SECRET, "appSecret");

		String displayNumber;
		try {
			displayNumber = meta.verifyNumber(phoneNumberId.trim(), tokenToUse);
		} catch (MetaWhatsAppClient.WhatsAppCredentialsRejected rejected) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "accessToken", "reason", rejected.getMessage()));
		}

		String webhookToken = existingWebhookToken().orElseGet(TenantWhatsAppSettingsService::randomToken);
		if (secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_VERIFY_TOKEN).isEmpty()) {
			secrets.put(tenantId, TenantSecretStore.Kind.WHATSAPP_VERIFY_TOKEN, randomToken());
		}
		if (isPresent(accessToken)) {
			secrets.put(tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN, accessToken.trim());
		}
		if (isPresent(appSecret)) {
			secrets.put(tenantId, TenantSecretStore.Kind.WHATSAPP_APP_SECRET, appSecret.trim());
		}

		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id,
						whatsapp_webhook_token, whatsapp_display_number, whatsapp_verified_at, updated_at)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, now(), now())
				ON CONFLICT (tenant_id) DO UPDATE SET
					whatsapp_phone_number_id = EXCLUDED.whatsapp_phone_number_id,
					whatsapp_waba_id = EXCLUDED.whatsapp_waba_id,
					whatsapp_webhook_token =
						COALESCE(tenant_settings.whatsapp_webhook_token, EXCLUDED.whatsapp_webhook_token),
					whatsapp_display_number = EXCLUDED.whatsapp_display_number,
					whatsapp_verified_at = now(),
					updated_at = now()
				""", phoneNumberId.trim(), wabaId.trim(), webhookToken, displayNumber);

		// The ids are recorded; neither secret is, not even as having-a-length.
		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				null, Map.of("whatsappPhoneNumberId", phoneNumberId.trim(), "whatsappWabaId", wabaId.trim()),
				"WhatsApp account connected.");

		submitTemplates(tenantId, wabaId.trim(), tokenToUse);
		return read();
	}

	/** Re-proves the stored credentials, for the Test button on a temple already connected. */
	@Transactional
	public TenantWhatsAppSettings test() {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		TenantWhatsAppSettings current = read();
		if (!current.connected()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "phoneNumberId"));
		}
		String token = secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN).orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "accessToken")));

		String displayNumber;
		try {
			displayNumber = meta.verifyNumber(current.phoneNumberId(), token);
		} catch (MetaWhatsAppClient.WhatsAppCredentialsRejected rejected) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "accessToken", "reason", rejected.getMessage()));
		}
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_display_number = ?, whatsapp_verified_at = now()
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", displayNumber);
		return read();
	}

	/**
	 * The verify token, to paste into Meta's callback configuration. Audited on every read, as the
	 * payment webhook secret is: a secret leaving the system is a fact worth keeping.
	 */
	@Transactional
	public String revealVerifyToken(AuthenticatedUser actor) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		String token = secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_VERIFY_TOKEN).orElseThrow(
				() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("field", "verifyToken")));
		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				null, Map.of("revealed", "whatsappVerifyToken"), "WhatsApp verify token revealed.");
		return token;
	}

	/** Everything the adapter needs to send as this temple, or empty if it has connected nothing. */
	@Transactional(readOnly = true)
	public Optional<SendingIdentity> sendingIdentity() {
		UUID tenantId = TenantContext.get().orElse(null);
		if (tenantId == null) {
			return Optional.empty();
		}
		TenantWhatsAppSettings current = read();
		if (!current.connected()) {
			return Optional.empty();
		}
		return secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN)
				.map(token -> new SendingIdentity(current.phoneNumberId(), token));
	}

	/** Who a message is sent as. */
	public record SendingIdentity(String phoneNumberId, String accessToken) {
	}

	/**
	 * Which temple a callback belongs to, found by the opaque token in its URL — before any signature
	 * has been checked, because the signature can only be checked with that temple's own app secret.
	 */
	// Deliberately not @Transactional: the connection takes its RLS settings as it is checked out,
	// so a transaction opened before the token is set would hold a connection the policy refuses.
	public Optional<CallbackAddressee> tenantForWebhookToken(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		// Set on the connection as it is checked out, like every other RLS scoping value — an
		// ad-hoc SELECT set_config here would run on a different connection than the query.
		TenantContext.setWhatsAppWebhookToken(token);
		try {
			return jdbc.query("""
					SELECT tenant_id FROM tenant_settings WHERE whatsapp_webhook_token = ?
					""", (rs, n) -> new CallbackAddressee(rs.getObject("tenant_id", UUID.class)), token)
					.stream().findFirst();
		} finally {
			TenantContext.clearWhatsAppWebhookToken();
		}
	}

	/** The temple a callback names. */
	public record CallbackAddressee(UUID tenantId) {
	}

	/** That temple's Meta app secret, which its callbacks are signed with. */
	public Optional<String> appSecretOf(UUID tenantId) {
		return secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_APP_SECRET);
	}

	/** That temple's verify token, echoed back during Meta's callback handshake. */
	public Optional<String> verifyTokenOf(UUID tenantId) {
		return secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_VERIFY_TOKEN);
	}

	/** Records that a correctly signed callback arrived — the amber light's only evidence. */
	@Transactional
	public void markWebhookSeen(UUID tenantId) {
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_webhook_seen_at = now() WHERE tenant_id = ?
				""", tenantId);
	}

	/**
	 * Records that a WhatsApp message from this temple actually reached Meta (T-136, V123).
	 *
	 * <p><strong>The only writer of {@code whatsapp_last_sent_at}, and it must stay that way.</strong>
	 * Called from {@link WhatsAppChannelAdapter} immediately after
	 * {@link MetaWhatsAppClient#sendTemplate} hands back a message id, and from nowhere else. The
	 * moment anything else stamps it — a settings screen, a connection test, a backfill — the column
	 * goes back to meaning "configured", which is the distinction it exists to draw.
	 *
	 * <p>Rajeev, 2026-09-10, on the Send on WhatsApp button: it is shown "only after a message has
	 * actually gone through it successfully", not merely configured. The three dates V55 already
	 * stores are all about set-up — see the column comment in V123 — so none of them could answer it.
	 *
	 * <p>Scoped by the tenant on the connection rather than by an id passed in, unlike
	 * {@link #markWebhookSeen}: a send always happens inside the tenant context of the notification
	 * being dispatched, where a webhook arrives before any tenant is known and has to say which one
	 * it means.
	 *
	 * <p>It joins the dispatcher's transaction rather than opening its own. That is the right
	 * coupling: if the surrounding dispatch rolls back, no attempt row and no SENT status survive
	 * either, and a stamp claiming a send nothing else records would be a lie with no witness.
	 */
	@Transactional
	public void markMessageSent() {
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_last_sent_at = now(), updated_at = now()
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");
	}

	/**
	 * Whether a WhatsApp message from this temple has ever gone out successfully (T-136).
	 *
	 * <p><strong>Read by the purchase-order screen, and deliberately not through the settings
	 * endpoint.</strong> {@code GET /api/v1/settings/whatsapp} is behind {@code
	 * MANAGE_TEMPLE_SETTINGS}, which whoever raises a purchase order need not hold — a Kitchen
	 * Manager has {@code MANAGE_PURCHASE_ORDERS} and no reason to have the other. Had the orders
	 * screen asked that endpoint, the button would have vanished for people whose WhatsApp works
	 * perfectly, for a reason that is about a permission and has nothing to do with WhatsApp. So the
	 * fact travels on {@link org.iskcon.kms.purchaseorder.PurchaseOrderDetailView}, which that screen
	 * already reads under its own authority.
	 *
	 * <p>This method itself is not exposed on any controller, so it carries no permission of its own.
	 * Isolation is the database's, as always: the row is found through {@code tenant_settings}'
	 * ordinary RLS policy and one temple can never read another's.
	 *
	 * <p>A boolean and not the date. The screen has one question — offer the button or not — and a
	 * timestamp sitting on a purchase-order payload invites being read as something about THIS
	 * order's WhatsApp send, which it is not. The date stays in the column for whoever needs to say
	 * "not for six months" later.
	 */
	@Transactional(readOnly = true)
	public boolean hasEverSentSuccessfully() {
		if (TenantContext.get().isEmpty()) {
			return false;
		}
		Boolean ever = jdbc.query("""
				SELECT whatsapp_last_sent_at IS NOT NULL AS ever FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", rs -> rs.next() && rs.getBoolean("ever"));
		return Boolean.TRUE.equals(ever);
	}

	// ---------------------------------------------------------------------

	/**
	 * Registers every message this application can send, so an administrator never has to.
	 *
	 * <p>Best-effort, and deliberately after the credentials are stored: templates are Meta's to
	 * approve in their own time, and a rejection is a thing to fix later, not a reason to refuse a
	 * connection that otherwise works. Re-running is safe — a template that already exists is
	 * reported as such rather than duplicated.
	 */
	private void submitTemplates(UUID tenantId, String wabaId, String accessToken) {
		int submitted = 0;
		for (NotificationTemplate template : NotificationTemplate.values()) {
			try {
				MetaWhatsAppClient.TemplateOutcome outcome = meta.createTemplate(
						wabaId, accessToken, template.whatsappTemplateName(), template.whatsappCategory(),
						"en", template.whatsappBodyText(), template.whatsappExampleValues());
				if (outcome != MetaWhatsAppClient.TemplateOutcome.REFUSED) {
					submitted++;
				}
			} catch (RuntimeException e) {
				log.warn("Could not submit template {} for temple {}: {}",
						template.whatsappTemplateName(), tenantId, e.toString());
			}
		}
		if (submitted > 0) {
			jdbc.update("""
					UPDATE tenant_settings SET whatsapp_templates_submitted_at = now()
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		}
		log.info("Submitted {} of {} WhatsApp templates for temple {}",
				submitted, NotificationTemplate.values().length, tenantId);
	}

	/** The supplied value if there is one, else the stored one — a secret nobody can see again. */
	private String keep(String supplied, UUID tenantId, TenantSecretStore.Kind kind, String field) {
		if (isPresent(supplied)) {
			return supplied.trim();
		}
		return secrets.get(tenantId, kind).orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", field)));
	}

	private Optional<String> existingWebhookToken() {
		return jdbc.query("""
				SELECT whatsapp_webhook_token FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("whatsapp_webhook_token")).stream().filter(t -> t != null).findFirst();
	}

	private String webhookUrl(String token) {
		return token == null ? null : apiBaseUrl + "/api/v1/public/webhooks/whatsapp/" + token;
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}

	private static Instant instant(Object value) {
		return value instanceof OffsetDateTime odt ? odt.toInstant() : null;
	}

	private static String randomToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
