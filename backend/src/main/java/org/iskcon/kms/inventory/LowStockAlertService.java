package org.iskcon.kms.inventory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The low-stock digest for one tenant (E3-S3). Assumes it runs within a tenant context — the nightly
 * sweep sets one per temple — so everything it reads and writes is that temple's.
 *
 * <p>Two rules from the story live here: <strong>suppressed when empty</strong> (a temple with
 * nothing low gets no message — silence means all's well), and <strong>once per day</strong> — the
 * run is claimed in {@code low_stock_digest_runs} before sending, so a retry or a second sweep can't
 * send a duplicate. The digest goes to every active Kitchen Staff and Temple Admin, through the
 * notification service, which applies each person's channel preference and consent.
 */
@Service
public class LowStockAlertService {

	private final JdbcTemplate jdbc;
	private final InventoryItemService inventoryItemService;
	private final NotificationService notificationService;

	public LowStockAlertService(
			JdbcTemplate jdbc, InventoryItemService inventoryItemService,
			NotificationService notificationService) {
		this.jdbc = jdbc;
		this.inventoryItemService = inventoryItemService;
		this.notificationService = notificationService;
	}

	/**
	 * Sends today's digest for the current tenant, or does nothing. Returns true only if a digest was
	 * actually queued — false when nothing is low, or when today's digest already went out.
	 */
	@Transactional
	public boolean sendDailyDigest() {
		List<StockItemView> low = inventoryItemService.lowStock();
		if (low.isEmpty()) {
			return false;
		}

		// Claim today before sending. If the row already exists, the digest has gone out — stop.
		int claimed = jdbc.update("""
				INSERT INTO low_stock_digest_runs (tenant_id, digest_date)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, CURRENT_DATE)
				ON CONFLICT (tenant_id, digest_date) DO NOTHING
				""");
		if (claimed == 0) {
			return false;
		}

		String temple = templeName();
		String items = low.stream()
				.map(i -> "%s (%s %s)".formatted(i.ingredientName(), i.onHand(), i.unit()))
				.collect(Collectors.joining(", "));
		Map<String, Object> params = Map.of("temple", temple, "count", low.size(), "items", items);

		for (UUID userId : digestRecipients()) {
			notificationService.notify(
					NotificationRecipient.user(userId), NotificationTemplate.LOW_STOCK_DIGEST, params, null);
		}
		return true;
	}

	/** The people who should see a low-stock digest: those who can act on it. */
	private List<UUID> digestRecipients() {
		return jdbc.query("""
				SELECT id FROM users
				WHERE role IN ('KITCHEN_STAFF', 'KITCHEN_MANAGER', 'TEMPLE_ADMIN') AND status = 'ACTIVE'
				""", (rs, n) -> rs.getObject("id", UUID.class));
	}

	private String templeName() {
		return jdbc.query("""
				SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("name")).stream().findFirst().orElse("your temple");
	}
}
