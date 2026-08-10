package org.iskcon.kms.order;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The nightly regeneration of every active tenant's order list (E5-S2). Enumerates tenants at the
 * platform level and regenerates each in its own transaction across a bean boundary; one tenant
 * failing is logged and skipped — the same shape as the other nightly sweeps.
 */
@Component
public class OrderListRegenerateRunner {

	private static final Logger log = LoggerFactory.getLogger(OrderListRegenerateRunner.class);

	private final JdbcTemplate jdbc;
	private final OrderListService orderListService;

	public OrderListRegenerateRunner(JdbcTemplate jdbc, OrderListService orderListService) {
		this.jdbc = jdbc;
		this.orderListService = orderListService;
	}

	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants WHERE status = 'ACTIVE'",
				(rs, n) -> rs.getObject("id", UUID.class));

		int done = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				orderListService.regenerateForCurrentTenant();
				done++;
			} catch (RuntimeException e) {
				log.warn("Order-list regeneration failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return done;
	}
}
