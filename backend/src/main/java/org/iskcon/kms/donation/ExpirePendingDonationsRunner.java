package org.iskcon.kms.donation;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sweeps abandoned PENDING online donations to EXPIRED across every tenant (E7-S2). A checkout the
 * donor never completes leaves a PENDING record; this reaps them past their TTL so the ledger and
 * reconciliation aren't cluttered with dangling intents. Same per-tenant shape as the other sweeps.
 */
@Component
public class ExpirePendingDonationsRunner {

	private static final Logger log = LoggerFactory.getLogger(ExpirePendingDonationsRunner.class);

	private final JdbcTemplate jdbc;
	private final MonetaryDonationService donationService;

	public ExpirePendingDonationsRunner(JdbcTemplate jdbc, MonetaryDonationService donationService) {
		this.jdbc = jdbc;
		this.donationService = donationService;
	}

	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants", (rs, n) -> rs.getObject("id", UUID.class));
		int expired = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				expired += donationService.expirePendingForCurrentTenant();
			} catch (RuntimeException e) {
				log.warn("Expiring pending donations failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return expired;
	}
}
