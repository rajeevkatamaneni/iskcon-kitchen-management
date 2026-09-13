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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ops.TemplateStatusCopy;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
	 * For the refused-template list and, since T-169a, the template fingerprints: strings only, so a
	 * plain mapper is enough and the service's constructor, which three integration tests build by
	 * hand, stays as it is.
	 */
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<TenantWhatsAppSettings.RefusedTemplate>> REFUSED_LIST =
			new TypeReference<>() {
			};

	/** name -> "sha256:..." or null, as V129 describes. A LinkedHashMap because it keeps the nulls. */
	private static final TypeReference<LinkedHashMap<String, String>> FINGERPRINT_MAP =
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
	private final WhatsAppTemplateComparison comparison;
	private final TemplateStatusCopy statusCopy;
	private final String apiBaseUrl;

	@Autowired
	public TenantWhatsAppSettingsService(JdbcTemplate jdbc, TenantSecretStore secrets,
			AuditService auditService, MetaWhatsAppClient meta, WhatsAppTemplateComparison comparison,
			TemplateStatusCopy statusCopy, @Value("${kms.api-base-url:}") String apiBaseUrl) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.auditService = auditService;
		this.meta = meta;
		this.comparison = comparison;
		this.statusCopy = statusCopy;
		this.apiBaseUrl = apiBaseUrl;
	}

	/**
	 * The constructor three integration tests in this package build the service with by hand (T-178).
	 *
	 * <p>It builds a real comparison and a real copy from the same collaborators rather than passing null,
	 * so a Reload in those tests takes exactly the production path, stored copy included, against whatever
	 * Meta the test supplies. Package-private so nothing outside the package can pick it, and Spring uses
	 * the {@code @Autowired} one.
	 */
	TenantWhatsAppSettingsService(JdbcTemplate jdbc, TenantSecretStore secrets, AuditService auditService,
			MetaWhatsAppClient meta, String apiBaseUrl) {
		this(jdbc, secrets, auditService, meta, new WhatsAppTemplateComparison(jdbc, secrets, meta),
				new TemplateStatusCopy(jdbc, auditService), apiBaseUrl);
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
					   whatsapp_templates_submitted_at, whatsapp_refused_templates::text AS refused_templates,
					   whatsapp_template_fingerprints::text AS fingerprints, whatsapp_templates_sent_waba_id,
					   whatsapp_templates_sent_phone_number_id
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
								refusedTemplates(rs.getString("refused_templates")),
								pending(rs)))
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
	 *
	 * <p><strong>Templates go to Meta on the first connection only (T-169a).</strong> Rajeev,
	 * 2026-09-13, approved: the first connection sends the templates automatically, and after that
	 * Save only saves; templates go only through Reload WhatsApp Templates. See
	 * {@link #templatesNeverSent} for what "first" means, including for a temple that sent templates
	 * before V129 kept any record of it.
	 *
	 * <p>A changed account is saved and nothing is sent to it. The view then reports
	 * {@code accountChanged}, and the administrator presses Reload. Sending twenty templates to a
	 * business account is a deliberate act with Meta's review and edit limits behind it, and the screen
	 * now separates editing a setting from sending anything, which is the point of Rajeev's read-only
	 * default.
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

		boolean firstConnection = templatesNeverSent();
		if (firstConnection) {
			submitTemplates(wabaId.trim(), phoneNumberId.trim(), tokenToUse, false);
		}
		return read();
	}

	/**
	 * The Reload WhatsApp Templates button (T-169a): sends Meta every template again, brings the wording
	 * Meta holds up to date, and records what Meta now holds.
	 *
	 * <p>Rajeev, 2026-09-13: <em>"For Watts App specifically, we need a new button Reload Wattsapp
	 * Templates. Click on that to reload watts app templates. We can show a date when the templates
	 * were uploaded last."</em> And, approved with it, "Reload must actually update changed wording".
	 *
	 * <p><strong>Every template, not only the ones counted as waiting.</strong> The counts on the view
	 * only say what is known from our own records, and our records cannot see a template somebody
	 * deleted in Meta's manager, or what an account holds that nobody has compared. Asking Meta about
	 * all twenty is what makes the button's quiet state, "last sent to Meta on …", true after a press.
	 * It costs what Save's submission always cost: one registration per template, plus a lookup and
	 * perhaps an edit for each template whose wording is not already known to be current. See
	 * {@link #submitTemplates}.
	 *
	 * <p><strong>Synchronous, and that is a known limit.</strong> Twenty or more sequential Meta calls
	 * inside one request and one transaction, 34 to 60 seconds on staging's Save. Nothing here is built
	 * around it, by instruction, because the press is rare and the administrator is waiting for the
	 * answer anyway.
	 *
	 * <p>Not connected, or connected with no stored token, is refused exactly as the Test button
	 * refuses it, with the same existing codes: nothing about Reload is a new kind of failure.
	 */
	@Transactional
	public TenantWhatsAppSettings reloadTemplates(AuthenticatedUser actor) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		TenantWhatsAppSettings current = read();
		if (!current.connected()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "phoneNumberId"));
		}
		String token = secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN).orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "accessToken")));

		submitTemplates(current.wabaId(), current.phoneNumberId(), token, true);

		// Before and after are both read from the row, never computed from what was asked for, so the
		// audit entry says what the screen said and then what is stored.
		TenantWhatsAppSettings after = read();
		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				pendingForAudit(current.templatesPending()), pendingForAudit(after.templatesPending()),
				"WhatsApp templates sent to Meta.");

		// T-178: the temple's stored copy of Meta's status, read by the operator's screens, is taken again
		// now, because a Reload is when the wording at Meta changes. It is T-173's comparison, twenty GETs
		// and no POST, inside this transaction so the copy commits with the Reload it describes. No
		// operator audit: the temple acted, and its Reload is already recorded just above. The Refresh an
		// operator presses is the audited path, in OpsService.
		statusCopy.replace(comparison.compare());
		return after;
	}

	private static Map<String, Object> pendingForAudit(TenantWhatsAppSettings.TemplatesPending pending) {
		return Map.of("whatsappTemplatesChanged", pending.changed(),
				"whatsappTemplatesRefused", pending.refused(),
				"whatsappAccountChanged", pending.accountChanged());
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
	 *
	 * <p><strong>A template Meta holds under a category of its own is kept too, as its own kind
	 * (T-168).</strong> Meta re-categorised {@code donation_thank_you} and {@code wishlist_gift_split}
	 * as marketing on staging, and refuses to register them again as utility. They are not refused —
	 * Meta holds them and they can be sent — so they are stored with a sentence that says what is true
	 * and with {@link TenantWhatsAppSettings.Kind#HELD_UNDER_ANOTHER_CATEGORY}, never with a refusal's
	 * advice to press Save, which would get the same answer forever.
	 *
	 * <p><strong>And they count toward the date.</strong> The date says Meta holds at least one of our
	 * templates, and it holds these. Leaving them out would mean a save where Meta held everything,
	 * two of them as marketing, could write no date if the other eighteen happened to be refused —
	 * which would say nothing went to Meta when two plainly had.
	 *
	 * <p><strong>What is recorded, and when Meta is asked about wording (T-169a, V129).</strong> Every
	 * send records, per template, a fingerprint of the wording Meta holds, or null when that is not
	 * known, and the account it went to. A template Meta has just created holds our wording. A template
	 * Meta refused, or could not be asked about, is null. A template Meta already held is where the two
	 * callers differ:
	 * <ul>
	 *   <li>On a first connection ({@code compareWithMeta} false) it is recorded as null, unknown, and
	 *       Meta is asked nothing more. That keeps the first Save exactly what T-159 and T-168 made it,
	 *       one registration per template, and an account that already held templates from some
	 *       earlier set-up is not reported as changed on the strength of nobody having looked.</li>
	 *   <li>On Reload ({@code compareWithMeta} true) it is taken as current without asking only when
	 *       the same account last recorded this exact fingerprint. Otherwise Meta is asked what it
	 *       holds, and the wording is replaced if it differs: see {@link #bringUpToDate}.</li>
	 * </ul>
	 *
	 * <p><strong>Why an edit and not a new name.</strong> The name is in {@link NotificationTemplate},
	 * the same for every temple, and every send asks for it. A new name is a release-wide rename: every
	 * temple's messages would fail over to SMS until each temple's Meta approved the new template, and a
	 * temple that had not yet pressed Reload would be sending a name its account does not hold. Meta's
	 * edit changes one temple's copy in place, under the name the code already sends, and "the API
	 * automatically re-approves the template unless it fails template review". Renaming stays the right
	 * tool for what an edit cannot do, such as moving an approved template's category, and is done in a
	 * release as T-159 did for the connection check.
	 */
	private void submitTemplates(String wabaId, String phoneNumberId, String accessToken, boolean compareWithMeta) {
		UUID tenantId = TenantContext.get().orElse(null);
		LastSend before = lastSend();
		boolean sameAccount = wabaId.equals(before.wabaId()) && phoneNumberId.equals(before.phoneNumberId());
		Map<String, String> fingerprints = new LinkedHashMap<>();
		int submittedNew = 0;
		int alreadyHeld = 0;
		int heldUnderAnotherCategory = 0;
		int notRegistered = 0;
		int reworded = 0;
		int wordingNotUpdated = 0;
		List<TenantWhatsAppSettings.RefusedTemplate> needsAttention = new ArrayList<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			String name = template.whatsappTemplateName();
			String fingerprint = template.whatsappFingerprint(TEMPLATE_LANGUAGE);
			try {
				MetaWhatsAppClient.TemplateSubmission result = meta.createTemplate(
						wabaId, accessToken, name, template.whatsappCategory(),
						TEMPLATE_LANGUAGE, template.whatsappBodyText(), template.whatsappExampleValues());
				switch (result.outcome()) {
					case SUBMITTED -> {
						submittedNew++;
						fingerprints.put(name, fingerprint);
					}
					case REFUSED -> {
						notRegistered++;
						fingerprints.put(name, null);
						needsAttention.add(new TenantWhatsAppSettings.RefusedTemplate(name,
								plainReason(result.metaReason()), TenantWhatsAppSettings.Kind.REFUSED));
					}
					case ALREADY_EXISTS, HELD_UNDER_ANOTHER_CATEGORY -> {
						boolean heldUnderItsOwnCategory =
								result.outcome() == MetaWhatsAppClient.TemplateOutcome.HELD_UNDER_ANOTHER_CATEGORY;
						if (heldUnderItsOwnCategory) {
							heldUnderAnotherCategory++;
						} else {
							alreadyHeld++;
						}
						HeldWording wording = !compareWithMeta
								? HeldWording.UNKNOWN
								: sameAccount && fingerprint.equals(before.fingerprints().get(name))
										? HeldWording.CURRENT
										: bringUpToDate(wabaId, accessToken, template);
						// For a template Meta holds under a category of its own, a fingerprint here says the
						// wording is ours; the category is not something a Reload can move, and it stays on
						// the list as its own kind rather than being counted as changed forever.
						fingerprints.put(name, wording.current() ? fingerprint : null);
						if (wording.reworded()) {
							reworded++;
						}
						if (wording.problem() != null) {
							wordingNotUpdated++;
							needsAttention.add(wording.problem());
						} else if (heldUnderItsOwnCategory) {
							needsAttention.add(new TenantWhatsAppSettings.RefusedTemplate(name,
									heldUnderReason(result.heldCategory()),
									TenantWhatsAppSettings.Kind.HELD_UNDER_ANOTHER_CATEGORY));
						}
					}
				}
			} catch (RuntimeException e) {
				log.warn("Could not submit template {} for temple {}: {}", name, tenantId, e.toString());
				notRegistered++;
				fingerprints.put(name, null);
				needsAttention.add(new TenantWhatsAppSettings.RefusedTemplate(name, NOT_REACHED,
						TenantWhatsAppSettings.Kind.NOT_REACHED));
			}
		}
		int registered = submittedNew + alreadyHeld + heldUnderAnotherCategory;
		if (registered > 0) {
			jdbc.update("""
					UPDATE tenant_settings SET whatsapp_templates_submitted_at = now()
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		}
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_refused_templates = ?::jsonb
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", json(needsAttention));
		jdbc.update(RECORD_FINGERPRINTS, fingerprintJson(fingerprints));
		jdbc.update("""
				UPDATE tenant_settings
				SET whatsapp_templates_sent_waba_id = ?, whatsapp_templates_sent_phone_number_id = ?
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", wabaId, phoneNumberId);
		log.info("Submitted {} of {} WhatsApp templates for temple {}: {} new, {} already held, "
						+ "{} held under another category, {} not registered",
				registered, NotificationTemplate.values().length, tenantId,
				submittedNew, alreadyHeld, heldUnderAnotherCategory, notRegistered);
		if (compareWithMeta) {
			log.info("Reload for temple {}: {} reworded at Meta, {} still waiting for new wording",
					tenantId, reworded, wordingNotUpdated);
		}
	}

	/** The one write of the fingerprints, as its own statement so it can be found and reasoned about. */
	private static final String RECORD_FINGERPRINTS = """
			UPDATE tenant_settings SET whatsapp_template_fingerprints = ?::jsonb
			WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
			""";

	/**
	 * Makes the wording Meta holds for one template match ours, on Reload (T-169a).
	 *
	 * <p><strong>Finding the template's id.</strong> We never store one. Meta lists a business account's
	 * templates filtered by name, and {@link MetaWhatsAppClient#findTemplate} takes the one entry whose
	 * name and language are exactly ours. That answer also carries the review status and the body.
	 *
	 * <p><strong>Then, in order:</strong>
	 * <ol>
	 *   <li>The body Meta holds is ours: nothing to do. This is every template on a temple that sent its
	 *       templates before V129 and has had no rewording since, which is how that temple's first Reload
	 *       records fingerprints without editing anything.</li>
	 *   <li>Meta is still reviewing it ({@code PENDING}, or {@code IN_APPEAL}): no edit is attempted,
	 *       because "Only templates with an APPROVED, REJECTED, or PAUSED status can be edited".</li>
	 *   <li>Any other status outside those three, such as {@code DISABLED}: not attempted either.</li>
	 *   <li>Otherwise Meta is asked to replace the wording. Its documented 2388039 covers both a review
	 *       in progress and too many edits, and the status just read tells them apart: on an
	 *       {@code APPROVED} template it can only be the limit, "up to 10 times in a 30-day window, or 1
	 *       time in a 24-hour window". On a status Meta did not name, the stored sentence says both.</li>
	 * </ol>
	 * https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-management
	 *
	 * <p>Each outcome that leaves Meta holding other wording is stored on the list with its own plain
	 * sentence, and records no fingerprint, so a later Reload tries again.
	 */
	private HeldWording bringUpToDate(String wabaId, String accessToken, NotificationTemplate template) {
		String name = template.whatsappTemplateName();
		Optional<MetaWhatsAppClient.HeldTemplate> found;
		try {
			found = meta.findTemplate(wabaId, accessToken, name, TEMPLATE_LANGUAGE);
		} catch (RuntimeException e) {
			log.warn("Could not ask Meta which wording it holds for template {}: {}", name, e.toString());
			return HeldWording.problem(name, NOT_TOLD_WHAT_META_HOLDS, TenantWhatsAppSettings.Kind.NOT_REACHED);
		}
		if (found.isEmpty()) {
			log.warn("Meta says it holds template {} but lists none of that name in language {}", name, TEMPLATE_LANGUAGE);
			return HeldWording.problem(name, NOT_TOLD_WHAT_META_HOLDS, TenantWhatsAppSettings.Kind.NOT_REACHED);
		}
		MetaWhatsAppClient.HeldTemplate held = found.get();
		String ours = template.whatsappBodyText();
		if (held.bodyText() != null && ours.strip().equals(held.bodyText().strip())) {
			return HeldWording.CURRENT;
		}

		String status = held.status() == null ? "" : held.status().toUpperCase(Locale.ROOT);
		if (STILL_IN_REVIEW_STATUSES.contains(status)) {
			return HeldWording.problem(name, STILL_IN_REVIEW, TenantWhatsAppSettings.Kind.REFUSED);
		}
		if (!status.isEmpty() && !EDITABLE_STATUSES.contains(status)) {
			return HeldWording.problem(name, CANNOT_BE_REWORDED, TenantWhatsAppSettings.Kind.REFUSED);
		}

		MetaWhatsAppClient.TemplateEdit edit;
		try {
			edit = meta.editTemplate(held.id(), accessToken, name, ours, template.whatsappExampleValues());
		} catch (RuntimeException e) {
			log.warn("Could not send new wording for template {}: {}", name, e.toString());
			return HeldWording.problem(name, NOT_REACHED, TenantWhatsAppSettings.Kind.NOT_REACHED);
		}
		return switch (edit.outcome()) {
			case EDITED -> HeldWording.REWORDED;
			case STATUS_CANNOT_BE_CHANGED -> HeldWording.problem(name,
					"APPROVED".equals(status) ? EDIT_LIMIT : IN_REVIEW_OR_EDIT_LIMIT,
					TenantWhatsAppSettings.Kind.REFUSED);
			case REFUSED -> HeldWording.problem(name, plainReason(edit.metaReason()),
					TenantWhatsAppSettings.Kind.REFUSED);
		};
	}

	/** Meta's statuses under which a template is being reviewed, so cannot be edited yet. */
	private static final Set<String> STILL_IN_REVIEW_STATUSES = Set.of("PENDING", "IN_APPEAL");

	/** "Only templates with an APPROVED, REJECTED, or PAUSED status can be edited." */
	private static final Set<String> EDITABLE_STATUSES = Set.of("APPROVED", "REJECTED", "PAUSED");

	static final String NOT_TOLD_WHAT_META_HOLDS =
			"Meta did not say which wording it holds for this message. Press Reload to try again.";

	static final String STILL_IN_REVIEW =
			"Meta is still reviewing this message, so its new wording has to wait. Press Reload again once the review is over.";

	static final String EDIT_LIMIT =
			"Meta allows a message to be reworded only once a day and ten times a month. Press Reload again tomorrow.";

	static final String IN_REVIEW_OR_EDIT_LIMIT =
			"Meta is not taking new wording for this message yet, because of a review or a recent change. Press Reload again tomorrow.";

	static final String CANNOT_BE_REWORDED =
			"Meta holds this message in a state that cannot be reworded. Report it with the message name shown here.";

	/**
	 * What became of one template Meta already held.
	 *
	 * @param current  Meta now holds exactly our wording, so its fingerprint is recorded
	 * @param reworded that is because this Reload replaced the wording
	 * @param problem  the list entry to store when Meta holds other wording, or null
	 */
	private record HeldWording(boolean current, boolean reworded, TenantWhatsAppSettings.RefusedTemplate problem) {

		static final HeldWording UNKNOWN = new HeldWording(false, false, null);
		static final HeldWording CURRENT = new HeldWording(true, false, null);
		static final HeldWording REWORDED = new HeldWording(true, true, null);

		static HeldWording problem(String name, String reason, TenantWhatsAppSettings.Kind kind) {
			return new HeldWording(false, false, new TenantWhatsAppSettings.RefusedTemplate(name, reason, kind));
		}
	}

	/** What the last send recorded: the fingerprints and the account. */
	private record LastSend(Map<String, String> fingerprints, String wabaId, String phoneNumberId) {
	}

	private LastSend lastSend() {
		return jdbc.query("""
				SELECT whatsapp_template_fingerprints::text AS fingerprints, whatsapp_templates_sent_waba_id,
					   whatsapp_templates_sent_phone_number_id
				FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> new LastSend(fingerprints(rs.getString("fingerprints")),
						rs.getString("whatsapp_templates_sent_waba_id"),
						rs.getString("whatsapp_templates_sent_phone_number_id")))
				.stream().findFirst().orElseGet(() -> new LastSend(Map.of(), null, null));
	}

	/**
	 * Whether this is a temple's first connection, the only Save that sends templates (T-169a).
	 *
	 * <p><strong>"First" means no template has ever been sent for this temple</strong>: no account
	 * recorded as sent to, which every send since V129 writes whatever Meta answered, and no
	 * {@code whatsapp_templates_submitted_at}, which every send since V55 wrote when Meta held anything.
	 *
	 * <p>Both, because each alone misses a case. The account alone would call every temple that sent
	 * before V129 "first" and resend twenty templates on its next Save, which is exactly what Rajeev
	 * ruled out. The date alone would call a temple whose first Save found Meta refusing or unreachable
	 * for every template "first" again, and Save would keep sending. With both, that temple's next
	 * attempt is Reload, as it is for everyone else.
	 *
	 * <p>One case reads as first and is: a temple that connected before V129 and never had a template
	 * registered at all has nothing at Meta, so its next Save sends.
	 */
	private boolean templatesNeverSent() {
		return Boolean.TRUE.equals(jdbc.query("""
				SELECT whatsapp_templates_sent_waba_id IS NULL AND whatsapp_templates_submitted_at IS NULL AS never
				FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", rs -> !rs.next() || rs.getBoolean("never")));
	}

	private static TenantWhatsAppSettings.TemplatesPending pending(ResultSet rs) throws SQLException {
		return pending(refusedTemplates(rs.getString("refused_templates")), fingerprints(rs.getString("fingerprints")),
				rs.getString("whatsapp_templates_sent_waba_id"), rs.getString("whatsapp_templates_sent_phone_number_id"),
				rs.getString("whatsapp_waba_id"), rs.getString("whatsapp_phone_number_id"));
	}

	/**
	 * What the Reload button is waiting to send (T-169a), from nothing but what this temple's row
	 * records.
	 *
	 * <p><strong>{@code refused}</strong> counts the stored entries of kind REFUSED or NOT_REACHED, as the
	 * screen's contract defines it. A template Meta holds under a category of its own is not counted:
	 * Reload cannot change a category, and a count that no press can clear would leave the button
	 * primary forever.
	 *
	 * <p><strong>{@code changed}</strong> counts templates in this release that are not already counted as
	 * refused and whose stored fingerprint is either different from today's or missing:
	 * <ul>
	 *   <li><strong>A temple with no fingerprints at all counts nothing.</strong> That is every temple that
	 *       sent before V129, South Bengaluru on staging among them: Meta holds all twenty there, and the
	 *       six T-159 reworded already carry the new wording, so "20 changed" would be false and even
	 *       "6 changed" would be. Nothing was recorded, so nothing is claimed. The next Reload asks Meta,
	 *       finds the wording current, and records it.</li>
	 *   <li>A fingerprint recorded as null, unknown, counts nothing, for the same reason.</li>
	 *   <li>A template with no key at all, on a temple that has fingerprints, is one the app did not have
	 *       when it last sent: new in this release, so waiting.</li>
	 *   <li>A refused template is left to {@code refused}, so the two numbers never count one template
	 *       twice and the screen can add them.</li>
	 * </ul>
	 *
	 * <p><strong>{@code accountChanged}</strong> compares the account last sent to with the one saved now.
	 * Never sent since V129 reads as not changed: there is no record to differ from.
	 */
	static TenantWhatsAppSettings.TemplatesPending pending(List<TenantWhatsAppSettings.RefusedTemplate> stored,
			Map<String, String> fingerprints, String sentWabaId, String sentPhoneNumberId,
			String wabaId, String phoneNumberId) {

		Set<String> refusedNames = stored.stream()
				.filter(TenantWhatsAppSettingsService::waitsForReload)
				.map(TenantWhatsAppSettings.RefusedTemplate::name)
				.collect(Collectors.toSet());
		int changed = 0;
		if (!fingerprints.isEmpty()) {
			for (NotificationTemplate template : NotificationTemplate.values()) {
				String name = template.whatsappTemplateName();
				if (refusedNames.contains(name)) {
					continue;
				}
				if (!fingerprints.containsKey(name)) {
					changed++;
					continue;
				}
				String held = fingerprints.get(name);
				if (held != null && !held.equals(template.whatsappFingerprint(TEMPLATE_LANGUAGE))) {
					changed++;
				}
			}
		}
		int refused = (int) stored.stream().filter(TenantWhatsAppSettingsService::waitsForReload).count();
		boolean accountChanged = sentWabaId != null
				&& (!sentWabaId.equals(wabaId) || !Objects.equals(sentPhoneNumberId, phoneNumberId));
		return new TenantWhatsAppSettings.TemplatesPending(changed, refused, accountChanged);
	}

	private static boolean waitsForReload(TenantWhatsAppSettings.RefusedTemplate entry) {
		return entry.kind() == TenantWhatsAppSettings.Kind.REFUSED
				|| entry.kind() == TenantWhatsAppSettings.Kind.NOT_REACHED;
	}

	private static String fingerprintJson(Map<String, String> fingerprints) {
		try {
			return JSON.writeValueAsString(fingerprints);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Could not write the template fingerprints", e);
		}
	}

	private static Map<String, String> fingerprints(String stored) {
		if (stored == null || stored.isBlank()) {
			return Map.of();
		}
		try {
			return JSON.readValue(stored, FINGERPRINT_MAP);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Could not read the template fingerprints", e);
		}
	}

	/**
	 * Since T-169a a later Save no longer sends templates, so every "try again" says Reload. That is
	 * the button that sends them, on a first connection's refusals as on any other.
	 */
	private static final String NOT_REACHED =
			"Meta could not be reached while this message was being registered. Press Reload to try again.";

	/**
	 * A template Meta holds under a category it chose, in words that tell the truth and give no
	 * advice that cannot work (T-168). Pressing Save changes nothing, and the category can only be
	 * appealed in Meta's own manager, so the sentence states the consequence and stops.
	 *
	 * <p>Why marketing matters enough to say: Meta "does not currently deliver marketing template
	 * messages to WhatsApp users with United States phone numbers", and limits how many a person
	 * receives elsewhere.
	 * https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/marketing-templates/per-user-limits
	 */
	static String heldUnderReason(String heldCategory) {
		if ("MARKETING".equalsIgnoreCase(heldCategory)) {
			return "Meta holds this message as marketing, which some countries do not deliver.";
		}
		return "Meta holds this message under a different category from the app's.";
	}

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
		return "Meta did not accept this message. Press Reload to try again, and if it is refused again, "
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
