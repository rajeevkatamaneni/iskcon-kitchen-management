package org.iskcon.kms.payment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which gateway collects for the temple in hand (E7).
 *
 * <p>A gateway used to be chosen once at startup, which was right while the platform had a single
 * merchant account. Money belongs to the temple that was given it, so the provider is now a property
 * of the temple, and the client is made from that temple's own keys.
 *
 * <p>A temple that has configured nothing falls back to the platform gateway — in a deployment
 * without credentials that is the stub, which takes no money. That is the honest failure: a temple
 * that has not set up payments cannot collect, and its donations sit unconfirmed rather than
 * quietly landing in somebody else's account.
 */
@Component
public class PaymentGatewayResolver {

	private static final Logger log = LoggerFactory.getLogger(PaymentGatewayResolver.class);

	private final JdbcTemplate jdbc;
	private final TenantSecretStore secrets;
	private final List<PaymentGatewayFactory> factories;
	private final PaymentGateway platformDefault;

	/**
	 * Built clients, keyed by the key id they were built from.
	 *
	 * <p>Cached because building one opens an HTTP client and reading its secret is a call to the
	 * secret manager, and doing both on every donation would put a hundred milliseconds in front of
	 * a devotee pressing Give. Keyed by key id rather than tenant, so a temple that rotates its key
	 * gets a new client on the next donation without anything having to be invalidated by hand.
	 */
	private final Map<String, PaymentGateway> built = new ConcurrentHashMap<>();

	public PaymentGatewayResolver(JdbcTemplate jdbc, TenantSecretStore secrets,
			List<PaymentGatewayFactory> factories, PaymentGateway platformDefault) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.factories = factories;
		this.platformDefault = platformDefault;
	}

	/** The gateway for the temple currently in context. */
	public PaymentGateway forCurrentTenant() {
		UUID tenantId = TenantContext.get().orElse(null);
		if (tenantId == null) {
			return platformDefault;
		}
		return forTenant(tenantId);
	}

	public PaymentGateway forTenant(UUID tenantId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT payment_provider, payment_key_id FROM tenant_settings
				WHERE tenant_id = ? AND payment_provider IS NOT NULL
				""", tenantId);
		if (rows.isEmpty()) {
			return platformDefault;
		}
		String provider = (String) rows.get(0).get("payment_provider");
		String keyId = (String) rows.get(0).get("payment_key_id");

		PaymentGateway cached = built.get(keyId);
		if (cached != null) {
			return cached;
		}

		Optional<PaymentGatewayFactory> factory = factories.stream()
				.filter(f -> f.provider().equalsIgnoreCase(provider))
				.findFirst();
		Optional<String> keySecret = secrets.get(tenantId, TenantSecretStore.Kind.PAYMENT_KEY_SECRET);
		if (factory.isEmpty() || keySecret.isEmpty()) {
			// Configured for a provider we cannot build, or with a secret that has gone missing.
			// Falling back is safer than throwing at the moment somebody is trying to give: the
			// donation is recorded and simply never confirmed, which is visible and recoverable.
			log.error("Temple {} is configured for {} but no client could be built; falling back.",
					tenantId, provider);
			return platformDefault;
		}
		return built.computeIfAbsent(keyId, k -> factory.get().forCredentials(k, keySecret.get()));
	}
}
