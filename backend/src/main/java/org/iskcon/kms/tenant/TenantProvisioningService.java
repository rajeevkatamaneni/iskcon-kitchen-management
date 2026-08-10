package org.iskcon.kms.tenant;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings a temple onto the platform.
 *
 * <p>This is the dedicated, audited path SYSTEM_DESIGN.md §3 calls for. It is the one place in
 * the system that writes rows for a tenant other than the caller's own — the super-admin has no
 * tenant of their own, so ordinary tenant-scoped access cannot create one.
 *
 * <p>Uses JDBC rather than JPA on purpose. Both inserts happen outside any tenant context, which
 * is precisely the situation RLS is designed to prevent; doing it through the entity manager
 * would mean either weakening the policies or fighting them. Keeping it to explicit SQL in one
 * audited service makes the exception visible rather than hidden behind an abstraction.
 */
@Service
public class TenantProvisioningService {

	private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

	private final JdbcTemplate jdbc;

	public TenantProvisioningService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Creates the temple and its first administrator as one unit.
	 *
	 * @return the new tenant's id
	 */
	@Transactional
	public UUID provision(ProvisionTenantRequest request, UUID provisionedBy) {
		validateTimezone(request.timezone());
		rejectDuplicateSlug(request.slug());

		UUID tenantId = insertTenant(request);
		insertFirstAdministrator(request, tenantId);

		// The audit trail for provisioning. E1-S7 replaces this with the shared audit_events
		// writer; until then the record still exists, in the logs, keyed to the actor.
		log.info("Tenant provisioned: tenant={} slug={} by={}",
				tenantId, request.slug(), provisionedBy);

		return tenantId;
	}

	private void validateTimezone(String timezone) {
		// Checked here rather than by annotation because the valid set is the JVM's zone
		// database, not a pattern. A wrong timezone silently shifts every Ekadashi calculation
		// for that temple, so it is worth failing loudly at provisioning time.
		try {
			ZoneId.of(timezone);
		} catch (Exception e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "timezone", "value", timezone),
					e);
		}
	}

	private void rejectDuplicateSlug(String slug) {
		Integer existing = jdbc.queryForObject(
				"SELECT count(*) FROM tenants WHERE slug = ?", Integer.class, slug);

		if (existing != null && existing > 0) {
			throw new ApplicationException(
					ErrorCode.SLUG_ALREADY_TAKEN,
					Map.of("slug", slug));
		}
	}

	private UUID insertTenant(ProvisionTenantRequest request) {
		return jdbc.queryForObject("""
				INSERT INTO tenants (
					slug, name, address, latitude, longitude, timezone,
					currency, locale, is_80g_approved, status)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
				RETURNING id
				""",
				UUID.class,
				request.slug(),
				request.name(),
				request.address(),
				request.latitude(),
				request.longitude(),
				request.timezone(),
				request.currency(),
				"en-IN");
	}

	private void insertFirstAdministrator(ProvisionTenantRequest request, UUID tenantId) {
		// firebase_uid is a placeholder until this person first signs in. They exist as a
		// Temple Admin here before they have ever authenticated with Firebase — which is the
		// right way round: the temple decides who administers it, not whoever signs up first.
		String pendingUid = "pending:" + UUID.randomUUID();

		try {
			jdbc.update("""
					INSERT INTO users (
						tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, ?, ?, ?, ?, 'TEMPLE_ADMIN', 'ACTIVE')
					""",
					tenantId,
					pendingUid,
					request.adminName(),
					request.adminEmail().toLowerCase(),
					request.adminPhone());

		} catch (org.springframework.dao.DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.EMAIL_ALREADY_REGISTERED,
					Map.of("email", request.adminEmail(), "tenantId", tenantId),
					e);
		}
	}
}
