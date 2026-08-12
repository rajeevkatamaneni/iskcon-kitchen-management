package org.iskcon.kms.wishlist;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Daily sweep archiving fulfilled wish-list items past their visibility window (E7-S5), per tenant. */
@Component
public class WishlistArchiveRunner {

	private static final Logger log = LoggerFactory.getLogger(WishlistArchiveRunner.class);

	private final JdbcTemplate jdbc;
	private final WishlistService wishlistService;

	public WishlistArchiveRunner(JdbcTemplate jdbc, WishlistService wishlistService) {
		this.jdbc = jdbc;
		this.wishlistService = wishlistService;
	}

	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants", (rs, n) -> rs.getObject("id", UUID.class));
		int archived = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				archived += wishlistService.archiveFulfilledForCurrentTenant();
			} catch (RuntimeException e) {
				log.warn("Wish-list archive sweep failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return archived;
	}
}
