package org.iskcon.kms.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant for the current thread of execution.
 *
 * <p>Set once per request by {@code TenantFilter} from the authenticated principal, and read
 * by {@link TenantAwareDataSource} when a database connection is handed out. Nothing else
 * should write to it.
 *
 * <p>Deliberately never populated from a request parameter, header, or body — SYSTEM_DESIGN.md
 * §4 requires the tenant to come from the verified token only. Accepting it from the request
 * would let a caller choose which temple's data to read.
 */
public final class TenantContext {

	private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(UUID tenantId) {
		CURRENT_TENANT.set(tenantId);
	}

	public static Optional<UUID> get() {
		return Optional.ofNullable(CURRENT_TENANT.get());
	}

	/**
	 * Clears the tenant. Must be called in a finally block at the end of every request —
	 * threads are pooled, and a leaked value would give the next request on this thread the
	 * previous request's tenant.
	 */
	public static void clear() {
		CURRENT_TENANT.remove();
	}
}
