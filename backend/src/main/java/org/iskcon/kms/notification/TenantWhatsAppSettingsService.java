package org.iskcon.kms.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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

	/**
	 * For the refused-template list only: two strings per element, so a plain mapper is enough and
	 * the service's constructor, which two integration tests build by hand, stays as it is.
	 */
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<TenantWhatsAppSettings.RefusedTemplate>> REFUSED_LIST =
			new TypeReference<>() {
			};

	/**
	 * The one language every template is registered in, so the one a test send must ask for.
	 *
	 * <p>A template exists at Meta per language, and asking for a language it was not registered in
	 * fails as "the template does not exist in the specified language". Registration and the test
	 * send share this constant so the two cannot drift.
	 */
	private static final String TEMPLATE_LANGUAGE = "en";

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

	/**
	 * What the Settings screen shows. Never includes a secret.
	 *
	 * <p><strong>Every date is read by naming its type, and that is the fix for T-159.</strong> This
	 * used to be a {@code queryForMap}, which asks the PostgreSQL driver for each column with a plain
	 * {@code getObject(int)}; for {@code timestamptz} the driver answers {@code java.sql.Timestamp}.
	 * The old conversion accepted only {@code OffsetDateTime} and turned anything else into null. So
	 * all three dates on this screen read as null for every temple, whatever the table held: on staging
	 * a save stamped {@code whatsapp_templates_submitted_at}, and the GET a minute later said it was
	 * empty. {@code WhatsAppTemplateSubmissionIT} reads the column both ways and pins the driver's
	 * behaviour, so the explanation is tested rather than asserted here.
	 */
	@Transactional(readOnly = true)
	public TenantWhatsAppSettings read() {
		return jdbc.query("""
				SELECT whatsapp_phone_number_id, whatsapp_waba_id, whatsapp_webhook_token,
					   whatsapp_display_number, whatsapp_verified_at, whatsapp_webhook_seen_at,
					   whatsapp_templates_submitted_at, whatsapp_refused_templates::text AS refused_templates
				FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("whatsapp_phone_number_id") == null
						? TenantWhatsAppSettings.none()
						: new TenantWhatsAppSettings(
								true,
								rs.getString("whatsapp_phone_number_id"),
								rs.getString("whatsapp_waba_id"),
								rs.getString("whatsapp_display_number"),
								webhookUrl(rs.getString("whatsapp_webhook_token")),
								instant(rs, "whatsapp_verified_at"),
								instant(rs, "whatsapp_webhook_seen_at"),
								instant(rs, "whatsapp_templates_submitted_at"),
								refusedTemplates(rs.getString("refused_templates"))))
				.stream().findFirst().orElseGet(TenantWhatsAppSettings::none);
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

	/**
	 * Sends a real test message from the temple's WhatsApp number to the phone an administrator
	 * typed, for the Test button on Settings (T-151).
	 *
	 * <p>Rajeev, 2026-09-12: <em>"Ask the use for a phone number to send a test message."</em> This
	 * replaced a Test button that asked Meta to describe the number and sent nothing, and it had to:
	 * the purchase-order screen offers Send on WhatsApp only once a message has actually gone out
	 * (T-136), so a temple using WhatsApp for orders alone had no way to earn the button except by
	 * sending an order the screen would not let it send.
	 *
	 * <p><strong>What it sends, and why it can be refused on a temple whose credentials are
	 * perfect.</strong> {@link NotificationTemplate#WHATSAPP_TEST}, a template of our own, because Meta
	 * sends nothing but an approved template to somebody who has not written to the temple in the last
	 * day. That template is registered at Connect like the others and Meta reviews it in its own time,
	 * so a fresh connection's first test can be refused for a reason that fixes itself. Every refusal
	 * — that, a number outside a test account's recipient list, an expired token, Meta unreachable —
	 * answers {@code KMS-500007} with the same next step, and Meta's own wording goes to the log
	 * under the incident id and never to the screen.
	 *
	 * <p><strong>What a success writes.</strong> {@code whatsapp_last_sent_at}, through
	 * {@link #markMessageSent} and nowhere else, because a message Meta accepted with an id is
	 * exactly the fact that column records. And {@code whatsapp_verified_at}, because Meta accepting a
	 * send under this token proves the credentials at least as well as the read that used to stamp
	 * it; without this, "Last checked" on the screen would never move again, since nothing but Save
	 * would touch it. The display number is left alone: a send does not return it, and Save refreshes
	 * it whenever the account changes.
	 *
	 * <p>Not connected, or connected with no stored token, is refused exactly as before this change.
	 */
	@Transactional
	public TenantWhatsAppSettings sendTestMessage(AuthenticatedUser actor, String phoneNumber) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		TenantWhatsAppSettings current = read();
		if (!current.connected()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "phoneNumberId"));
		}
		String token = secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN).orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "accessToken")));

		String to = phoneNumber.trim();
		String temple = jdbc.queryForObject("""
				SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", String.class);
		NotificationTemplate template = NotificationTemplate.WHATSAPP_TEST;
		Map<String, Object> params = Map.of("temple", temple == null ? "the temple" : temple);
		// Through OutboundMessage for the positional list, the one place named parameters become
		// Meta's {{1}}, {{2}} — so this send cannot order them differently from a real notification.
		OutboundMessage message = new OutboundMessage(template, params, template.render(params));

		String messageId;
		// The try holds the Meta call and nothing else, for the reason WhatsAppChannelAdapter gives:
		// a stamp or an audit write that failed inside it would be reported as Meta refusing a message
		// Meta had in fact accepted.
		try {
			messageId = meta.sendTemplate(current.phoneNumberId(), token, to,
					template.whatsappTemplateName(), TEMPLATE_LANGUAGE,
					message.orderedParameters());
		} catch (MetaWhatsAppClient.WhatsAppSendFailed | MetaWhatsAppClient.WhatsAppCredentialsRejected e) {
			throw new ApplicationException(ErrorCode.WHATSAPP_TEST_NOT_DELIVERED,
					Map.of("reason", String.valueOf(e.getMessage())), e);
		}

		markMessageSent();
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_verified_at = now()
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");

		// A message went out in the temple's name to a number somebody typed, which is worth a
		// permanent record of who and to whom. Meta's id is kept so a delivery question can be traced.
		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				null, Map.of("whatsappTestSentTo", to, "whatsappMessageId", messageId),
				"WhatsApp test message sent.");
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
	 * Called immediately after {@link MetaWhatsAppClient#sendTemplate} hands back a message id, and
	 * only then — from {@link WhatsAppChannelAdapter} for every notification, and from
	 * {@link #sendTestMessage} for the Settings test (T-151), which is a real message to a real phone
	 * and so the same fact. What must never call it is anything that proves configuration rather than
	 * a send: a credential check, a template submission, a callback arriving, a backfill. The moment
	 * one of those stamps it, the column goes back to meaning "configured", which is the distinction
	 * it exists to draw.
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
	 *
	 * <p><strong>What {@code whatsapp_templates_submitted_at} means.</strong> Stamped when at least one
	 * template was accepted by Meta or was already there. V55 says the column "records that we asked,
	 * never that they said yes", and the screen reads it as "your message templates went to Meta on
	 * …". A save where Meta refused every one registered nothing, so no date is written for it, and a
	 * date already there from an earlier save that did register some is left standing — it is still
	 * true.
	 *
	 * <p><strong>What is refused is kept, and replaced on every save (T-159, V128).</strong> Until this
	 * the only record of Meta refusing six templates on staging was a WARN in the log. Each refusal is
	 * stored with a sentence from {@link #plainReason}, never Meta's developer text, and a template Meta
	 * could not be asked about at all is kept too, because to an administrator the consequence is the
	 * same: that message will not go by WhatsApp until a later save registers it. The whole list is
	 * written every time, so a later clean save leaves it empty.
	 */
	private void submitTemplates(UUID tenantId, String wabaId, String accessToken) {
		int submitted = 0;
		List<TenantWhatsAppSettings.RefusedTemplate> refused = new ArrayList<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			String name = template.whatsappTemplateName();
			try {
				MetaWhatsAppClient.TemplateSubmission result = meta.createTemplate(
						wabaId, accessToken, name, template.whatsappCategory(),
						TEMPLATE_LANGUAGE, template.whatsappBodyText(), template.whatsappExampleValues());
				if (result.outcome() == MetaWhatsAppClient.TemplateOutcome.REFUSED) {
					refused.add(new TenantWhatsAppSettings.RefusedTemplate(name, plainReason(result.metaReason())));
				} else {
					submitted++;
				}
			} catch (RuntimeException e) {
				log.warn("Could not submit template {} for temple {}: {}", name, tenantId, e.toString());
				refused.add(new TenantWhatsAppSettings.RefusedTemplate(name, NOT_REACHED));
			}
		}
		if (submitted > 0) {
			jdbc.update("""
					UPDATE tenant_settings SET whatsapp_templates_submitted_at = now()
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		}
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_refused_templates = ?::jsonb
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", json(refused));
		log.info("Submitted {} of {} WhatsApp templates for temple {}",
				submitted, NotificationTemplate.values().length, tenantId);
	}

	private static final String NOT_REACHED =
			"Meta could not be reached while this message was being registered. Press Save to try again.";

	private static final String NEEDS_AN_APP_CHANGE = " This needs a change to the app, not to your WhatsApp account.";

	/**
	 * Meta's refusal, as a sentence an administrator can act on.
	 *
	 * <p>Matched on the phrases Meta used on staging, 2026-09-12. The first three are faults in our
	 * own wording, which {@code MetaTemplateRulesTest} now exists to stop, so the sentence says plainly
	 * that nothing in the temple's account needs changing. Anything else is unknown to us, and gets a
	 * next step rather than a guess. Meta's own text is already in the log from
	 * {@link MetaWhatsAppClient#createTemplate}.
	 */
	static String plainReason(String metaReason) {
		String meta = metaReason == null ? "" : metaReason.toLowerCase(Locale.ROOT);
		if (meta.contains("too many variable")) {
			return "Meta found too little fixed wording around the details the app fills in." + NEEDS_AN_APP_CHANGE;
		}
		if (meta.contains("start or end")) {
			return "Meta will not accept a message that begins or ends with a detail the app fills in."
					+ NEEDS_AN_APP_CHANGE;
		}
		if (meta.contains("consecutive newline") || meta.contains("only have parameters") || meta.contains("emoji")) {
			return "Meta found no fixed wording of its own, too many blank lines or too many emoji."
					+ NEEDS_AN_APP_CHANGE;
		}
		return "Meta did not accept this message. Press Save to try again, and if it is refused again, "
				+ "report it with the message name shown here.";
	}

	private static String json(List<TenantWhatsAppSettings.RefusedTemplate> refused) {
		try {
			return JSON.writeValueAsString(refused);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Could not write the refused-template list", e);
		}
	}

	private static List<TenantWhatsAppSettings.RefusedTemplate> refusedTemplates(String stored) {
		if (stored == null || stored.isBlank()) {
			return List.of();
		}
		try {
			return JSON.readValue(stored, REFUSED_LIST);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Could not read the refused-template list", e);
		}
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

	/** A timestamptz column, asked for as an OffsetDateTime by name — see {@link #read()} for why. */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static String randomToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
