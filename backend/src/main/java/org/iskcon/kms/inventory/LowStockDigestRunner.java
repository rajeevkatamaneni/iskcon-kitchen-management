package org.iskcon.kms.inventory;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The nightly sweep behind the low-stock digest (E3-S3): for each active temple, send its digest.
 *
 * <p>Separate from {@link LowStockAlertService} on purpose — this enumerates tenants at the platform
 * level (the {@code tenants} registry isn't tenant-scoped) and then calls the per-tenant service
 * across a bean boundary, so each temple's digest runs in its own transaction. One temple failing is
 * logged and skipped, never allowed to sink the rest of the sweep.
 */
@Component
public class LowStockDigestRunner {

	private static final Logger log = LoggerFactory.getLogger(LowStockDigestRunner.class);

	private final JdbcTemplate jdbc;
	private final LowStockAlertService alertService;

	public LowStockDigestRunner(JdbcTemplate jdbc, LowStockAlertService alertService) {
		this.jdbc = jdbc;
		this.alertService = alertService;
	}

	/** Runs the digest for every active tenant. Returns how many temples were actually sent one. */
	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants WHERE status = 'ACTIVE'",
				(rs, n) -> rs.getObject("id", UUID.class));

		int sent = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				if (alertService.sendDailyDigest()) {
					sent++;
				}
			} catch (RuntimeException e) {
				log.warn("Low-stock digest failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return sent;
	}
}
