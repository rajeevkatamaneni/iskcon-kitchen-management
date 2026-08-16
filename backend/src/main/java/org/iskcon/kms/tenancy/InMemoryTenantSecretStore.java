package org.iskcon.kms.tenancy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default secret store: in this process and nowhere else.
 *
 * <p>Keeping it the default is what keeps the test suite hermetic and local development free of a
 * cloud dependency, exactly as {@code StubPaymentGateway} does for payments. A deployment selects
 * the real one with {@code kms.secrets.store=gcp}.
 */
@Component
@ConditionalOnProperty(name = "kms.secrets.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryTenantSecretStore implements TenantSecretStore {

	private final Map<String, String> secrets = new ConcurrentHashMap<>();

	@Override
	public void put(UUID tenantId, Kind kind, String value) {
		secrets.put(key(tenantId, kind), value);
	}

	@Override
	public Optional<String> get(UUID tenantId, Kind kind) {
		return Optional.ofNullable(secrets.get(key(tenantId, kind)));
	}

	@Override
	public void deleteAll(UUID tenantId) {
		for (Kind kind : Kind.values()) {
			secrets.remove(key(tenantId, kind));
		}
	}

	private static String key(UUID tenantId, Kind kind) {
		return tenantId + "/" + kind.suffix();
	}
}
