package org.iskcon.kms.purchaseorder;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The nightly sweep that cancels drafts nobody ever sent, temple by temple (T-137, D-24a).
 *
 * <p>Split from the job for the reason every other sweep in this application is: the job is a Quartz
 * class that Quartz instantiates itself, and the work is a Spring bean that can be called from a
 * test without a scheduler. {@code WishlistArchiveRunner} and {@code ExpirePendingDonationsRunner}
 * are the same shape.
 *
 * <p><strong>One tenant at a time, and that is not a loop written for tidiness.</strong>
 * {@code purchase_orders} is under RLS and the application connects as an unprivileged role, so a
 * sweep that did not adopt a tenant would run with {@code app.tenant_id} unset, match nothing
 * through the policy's {@code NULLIF}, and report a cheerful zero having looked at no temple's
 * orders at all. That failure is silent, which is why it is spelled out here.
 *
 * <p>A temple whose sweep throws is logged and the loop carries on. One temple's bad data must not
 * stop every other temple's drafts from being released back onto their shopping lists.
 */
@Component
public class PurchaseOrderAutoCancelRunner {

	private static final Logger log = LoggerFactory.getLogger(PurchaseOrderAutoCancelRunner.class);

	private final JdbcTemplate jdbc;
	private final PurchaseOrderService purchaseOrders;

	public PurchaseOrderAutoCancelRunner(JdbcTemplate jdbc, PurchaseOrderService purchaseOrders) {
		this.jdbc = jdbc;
		this.purchaseOrders = purchaseOrders;
	}

	/** @return how many drafts were cancelled across every temple */
	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants", (rs, n) -> rs.getObject("id", UUID.class));
		int cancelled = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				cancelled += purchaseOrders.autoCancelAbandonedDrafts();
			} catch (RuntimeException e) {
				log.warn("Auto-cancel sweep failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return cancelled;
	}
}
