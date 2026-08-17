package org.iskcon.kms.payment;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A temple's own payment gateway (E7): choosing the provider, holding the credentials, and proving
 * they work.
 *
 * <p>The division of what lives where is the whole design. The database keeps only what is safe to
 * read — the provider, the public key id, the opaque token in this temple's webhook URL, and two
 * dates. The key secret and the webhook secret go to {@link TenantSecretStore}, which in a
 * deployment is Secret Manager: a dump of our schema leaks nothing spendable, and every read of a
 * secret is logged where this application cannot reach.
 */
@Service
public class TenantPaymentSettingsService {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(TenantPaymentSettingsService.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;
	private final TenantSecretStore secrets;
	private final AuditService auditService;
	private final List<PaymentProviderProbe> probes;
	private final List<PaymentEventHandler> handlers;
	private final String apiBaseUrl;

	public TenantPaymentSettingsService(JdbcTemplate jdbc, TenantSecretStore secrets,
			AuditService auditService, List<PaymentProviderProbe> probes,
			List<PaymentEventHandler> handlers,
			@Value("${kms.api-base-url:}") String apiBaseUrl) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.auditService = auditService;
		this.probes = probes;
		this.handlers = handlers;
		this.apiBaseUrl = apiBaseUrl;
	}

	/**
	 * What a temple must subscribe to, grouped by what each group is for, essentials first.
	 *
	 * <p>Read by the Settings screen and by webhook registration, so the list a temple is told to
	 * tick and the list this application acts on are the same list. It used to be typed into the
	 * screen by hand, which quietly omitted the {@code subscription.*} events — a temple following
	 * those instructions could take a monthly mandate and never record a single cycle of it.
	 */
	public List<WebhookSubscription> subscriptions() {
		return handlers.stream()
				.map(PaymentEventHandler::subscription)
				.sorted(java.util.Comparator.comparing(WebhookSubscription::essential).reversed()
						.thenComparing(WebhookSubscription::purpose))
				.toList();
	}

	/**
	 * Just the event types, for a registration call.
	 *
	 * <p>Everything, essential or not: registering by API is not typing into a dashboard, so there is
	 * no cost to asking for events a temple may never use, and a provider that does not offer them
	 * will say so.
	 */
	public List<String> subscribedEventTypes() {
		return handlers.stream()
				.flatMap(h -> h.subscribedEventTypes().stream())
				.distinct()
				.sorted()
				.toList();
	}

	/** What the Settings screen shows. Never includes a secret. */
	@Transactional(readOnly = true)
	public TenantPaymentSettings read() {
		Map<String, Object> row;
		try {
			row = jdbc.queryForMap("""
					SELECT payment_provider, payment_key_id, payment_webhook_token,
						   payment_verified_at, payment_webhook_seen_at,
						   payment_webhook_registered_at, updated_at
					FROM tenant_settings
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		} catch (EmptyResultDataAccessException noRowYet) {
			return TenantPaymentSettings.none();
		}
		String provider = (String) row.get("payment_provider");
		if (provider == null) {
			return TenantPaymentSettings.none();
		}
		return new TenantPaymentSettings(
				true,
				provider,
				(String) row.get("payment_key_id"),
				instant(row.get("updated_at")),
				webhookUrl((String) row.get("payment_webhook_token")),
				instant(row.get("payment_verified_at")),
				instant(row.get("payment_webhook_seen_at")),
				instant(row.get("payment_webhook_registered_at")));
	}

	/**
	 * Stores this temple's gateway.
	 *
	 * <p>The credentials are proven against the provider before anything is written — a key that does
	 * not work is a mistake to correct now, not a mystery to debug at the first donation. The webhook
	 * token and secret are minted once and kept across later edits, because they are already pasted
	 * into the temple's provider dashboard and changing them silently would stop confirmations.
	 */
	@Transactional
	public TenantPaymentSettings save(AuthenticatedUser actor, String provider, String keyId,
			String keySecret) {

		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));

		PaymentProviderProbe probe = probeFor(provider);
		String secretToUse = keySecret != null && !keySecret.isBlank()
				? keySecret.trim()
				: secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_KEY_SECRET).orElseThrow(
						() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "keySecret")));

		try {
			probe.verify(keyId.trim(), secretToUse);
		} catch (PaymentProviderProbe.PaymentCredentialsRejected rejected) {
			throw new ApplicationException(ErrorCode.PAYMENT_CREDENTIALS_REJECTED,
					Map.of("provider", provider, "reason", rejected.getMessage()));
		}

		// Keep the token and webhook secret a temple has already given their provider.
		String token = existingToken().orElseGet(TenantPaymentSettingsService::randomToken);
		if (secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET).isEmpty()) {
			secrets.put(tenantId, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET, randomToken());
		}
		if (keySecret != null && !keySecret.isBlank()) {
			secrets.put(tenantId, TenantSecretStore.Kind.PAYMENT_KEY_SECRET, keySecret.trim());
		}

		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, payment_provider, payment_key_id,
						payment_webhook_token, payment_verified_at, updated_at)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, now(), now())
				ON CONFLICT (tenant_id) DO UPDATE SET
					payment_provider = EXCLUDED.payment_provider,
					payment_key_id = EXCLUDED.payment_key_id,
					payment_webhook_token = COALESCE(tenant_settings.payment_webhook_token, EXCLUDED.payment_webhook_token),
					payment_verified_at = now(),
					updated_at = now()
				""", provider, keyId.trim(), token);

		registerWebhookIfProviderAllows(probe, tenantId, keyId.trim(), secretToUse, token);

		// The key id is recorded; the secret never is, not even as having-a-length.
		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				null, Map.of("paymentProvider", provider, "paymentKeyId", keyId.trim()),
				"Payment gateway credentials saved.");

		return read();
	}

	/**
	 * Asks the provider to call us, where the provider is one that lets us ask.
	 *
	 * <p>Best-effort on purpose. The credentials have already been proven and stored by this point,
	 * and they are useful whether or not the callback is configured — so a provider that refuses to
	 * register a webhook must not take a good save down with it. What happens instead is that
	 * {@code payment_webhook_registered_at} stays null, and the Settings screen shows the temple the
	 * manual steps, which is exactly what every Razorpay temple sees today.
	 */
	private void registerWebhookIfProviderAllows(
			PaymentProviderProbe probe, UUID tenantId, String keyId, String keySecret, String token) {

		if (!(probe instanceof WebhookRegistrar registrar)) {
			return;
		}
		Optional<String> webhookSecret = secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET);
		if (webhookSecret.isEmpty()) {
			return;
		}
		try {
			registrar.registerWebhook(
					keyId, keySecret, webhookUrl(token), webhookSecret.get(), subscribedEventTypes());
			jdbc.update("""
					UPDATE tenant_settings SET payment_webhook_registered_at = now()
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		} catch (RuntimeException e) {
			log.warn("Could not register the webhook for temple {} with {}; the manual steps still apply: {}",
					tenantId, probe.provider(), e.toString());
		}
	}

	/**
	 * Re-proves the stored credentials, for the Test button on a temple that has already saved.
	 * Records the answer either way, so the screen can say when it last worked.
	 */
	@Transactional
	public TenantPaymentSettings test() {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		TenantPaymentSettings current = read();
		if (!current.configured()) {
			throw new ApplicationException(ErrorCode.PAYMENT_NOT_CONFIGURED, Map.of());
		}
		String keySecret = secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_KEY_SECRET)
				.orElseThrow(() -> new ApplicationException(ErrorCode.PAYMENT_NOT_CONFIGURED, Map.of()));
		try {
			probeFor(current.provider()).verify(current.keyId(), keySecret);
		} catch (PaymentProviderProbe.PaymentCredentialsRejected rejected) {
			throw new ApplicationException(ErrorCode.PAYMENT_CREDENTIALS_REJECTED,
					Map.of("provider", current.provider(), "reason", rejected.getMessage()));
		}
		jdbc.update("""
				UPDATE tenant_settings SET payment_verified_at = now(), updated_at = now()
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");
		return read();
	}

	/**
	 * The webhook secret, for pasting into the provider's dashboard.
	 *
	 * <p>The one secret this system will hand back, because a temple cannot configure their provider
	 * without it — so every reveal is written to the audit log with who asked.
	 */
	@Transactional
	public String revealWebhookSecret(AuthenticatedUser actor) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		String secret = secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET)
				.orElseThrow(() -> new ApplicationException(ErrorCode.PAYMENT_NOT_CONFIGURED, Map.of()));
		auditService.record(actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.TENANT, tenantId,
				null, Map.of("revealed", "paymentWebhookSecret"),
				"Payment webhook secret revealed for provider setup.");
		return secret;
	}

	// ---- the webhook's side -------------------------------------------------

	/**
	 * The temple a webhook token belongs to, and who signs for it — read before any tenant is known,
	 * through the narrow escape the token opens.
	 *
	 * <p>Both facts come back together on purpose. The provider decides which scheme checks the
	 * signature, and fetching it separately would need a second trip through the same escape for a
	 * row we have already seen.
	 */
	public Optional<WebhookAddressee> tenantForWebhookToken(String token) {
		TenantContext.setPaymentWebhookToken(token);
		try {
			List<WebhookAddressee> found = jdbc.query("""
					SELECT tenant_id, payment_provider FROM tenant_settings
					WHERE payment_webhook_token = ?
					""",
					(rs, n) -> new WebhookAddressee(
							rs.getObject("tenant_id", UUID.class), rs.getString("payment_provider")),
					token);
			return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
		} finally {
			TenantContext.clearPaymentWebhookToken();
		}
	}

	/** Who a signed webhook is for, and whose scheme signed it. */
	public record WebhookAddressee(UUID tenantId, String provider) {
	}

	/** This temple's webhook secret, for verifying a signature. Never leaves the server. */
	public Optional<String> webhookSecretOf(UUID tenantId) {
		return secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET);
	}

	/** Records that a correctly signed webhook actually arrived — the amber light on the screen. */
	public void markWebhookSeen(UUID tenantId) {
		TenantContext.set(tenantId);
		try {
			jdbc.update("""
					UPDATE tenant_settings SET payment_webhook_seen_at = now()
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""");
		} finally {
			TenantContext.clear();
		}
	}

	// ---- internals ----------------------------------------------------------

	private PaymentProviderProbe probeFor(String provider) {
		return probes.stream()
				.filter(p -> p.provider().equalsIgnoreCase(provider))
				.findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.PAYMENT_PROVIDER_UNSUPPORTED,
						Map.of("provider", String.valueOf(provider))));
	}

	private Optional<String> existingToken() {
		List<String> found = jdbc.query("""
				SELECT payment_webhook_token FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				  AND payment_webhook_token IS NOT NULL
				""", (rs, n) -> rs.getString(1));
		return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
	}

	private String webhookUrl(String token) {
		if (token == null) {
			return null;
		}
		String base = apiBaseUrl == null || apiBaseUrl.isBlank() ? "" : apiBaseUrl.replaceAll("/+$", "");
		return base + "/api/v1/public/webhooks/payments/" + token;
	}

	private static String randomToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** A timestamptz comes back as one of two types depending on the driver's mood; take either. */
	private static Instant instant(Object value) {
		if (value instanceof OffsetDateTime odt) {
			return odt.toInstant();
		}
		if (value instanceof java.sql.Timestamp ts) {
			return ts.toInstant();
		}
		return null;
	}
}
