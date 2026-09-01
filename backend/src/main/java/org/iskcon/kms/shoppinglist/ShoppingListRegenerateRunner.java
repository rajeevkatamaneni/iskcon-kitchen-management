package org.iskcon.kms.shoppinglist;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The nightly regeneration of every active tenant's shopping list (E5-S2). Enumerates tenants at the
 * platform level and regenerates each in its own transaction across a bean boundary; one tenant
 * failing is logged and skipped — the same shape as the other nightly sweeps.
 */
@Component
public class ShoppingListRegenerateRunner {

	private static final Logger log = LoggerFactory.getLogger(ShoppingListRegenerateRunner.class);

	private final JdbcTemplate jdbc;
	private final ShoppingListService shoppingListService;

	public ShoppingListRegenerateRunner(JdbcTemplate jdbc, ShoppingListService shoppingListService) {
		this.jdbc = jdbc;
		this.shoppingListService = shoppingListService;
	}

	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants",
				(rs, n) -> rs.getObject("id", UUID.class));

		int done = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				shoppingListService.regenerateForCurrentTenant();
				done++;
			} catch (RuntimeException e) {
				log.warn("Shopping-list regeneration failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return done;
	}
}
