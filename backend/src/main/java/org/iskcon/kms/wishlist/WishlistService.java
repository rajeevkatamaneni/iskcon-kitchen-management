package org.iskcon.kms.wishlist;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wish-list items (E7-S5): admin CRUD, manual public ordering, and the fulfilment lifecycle. When a
 * sponsorship (E7-S6) brings an item's sponsored units up to what's wanted the item flips FULFILLED;
 * it stays visible for a tenant-configured window, then auto-archives. Archived items vanish
 * publicly but stay in ledger history.
 */
@Service
public class WishlistService {

	private final JdbcTemplate jdbc;

	public WishlistService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<WishlistItemView> list(boolean includeArchived) {
		String where = includeArchived ? "" : " WHERE i.status <> 'ARCHIVED'";
		return jdbc.query(SELECT + where + " ORDER BY i.sort_order, i.created_at", MAPPER);
	}

	/** Public list: ACTIVE and briefly-visible FULFILLED items, in manual order (E7-S6). */
	@Transactional(readOnly = true)
	public List<WishlistItemView> publicList() {
		return jdbc.query(SELECT + " WHERE i.status IN ('ACTIVE', 'FULFILLED') ORDER BY i.sort_order, i.created_at",
				MAPPER);
	}

	@Transactional(readOnly = true)
	public WishlistItemView get(UUID id) {
		return findItem(id).orElseThrow(() -> notFound(id));
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateWishlistItemRequest request) {
		UUID id = UUID.randomUUID();
		Integer nextSort = jdbc.queryForObject(
				"SELECT COALESCE(MAX(sort_order), 0) + 1 FROM wishlist_items", Integer.class);
		jdbc.update("""
				INSERT INTO wishlist_items (id, tenant_id, title, description, image_ref, price_inr,
					category, quantity_wanted, sort_order, note, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, request.title().trim(), trimToNull(request.description()), trimToNull(request.imageRef()),
				request.priceInr(), request.category(), request.quantityWanted(), nextSort,
				trimToNull(request.note()), actor.getUserId());
		return id;
	}

	@Transactional
	public void update(UUID id, UpdateWishlistItemRequest request) {
		int updated = jdbc.update("""
				UPDATE wishlist_items SET title = ?, description = ?, image_ref = ?, price_inr = ?,
					category = ?, quantity_wanted = ?, note = ?, updated_at = now() WHERE id = ?
				""", request.title().trim(), trimToNull(request.description()), trimToNull(request.imageRef()),
				request.priceInr(), request.category(), request.quantityWanted(), trimToNull(request.note()), id);
		if (updated == 0) {
			throw notFound(id);
		}
	}

	@Transactional
	public void archive(UUID id) {
		int updated = jdbc.update(
				"UPDATE wishlist_items SET status = 'ARCHIVED', updated_at = now() WHERE id = ?", id);
		if (updated == 0) {
			throw notFound(id);
		}
	}

	@Transactional
	public void reorder(List<UUID> itemIds) {
		int order = 0;
		for (UUID id : itemIds) {
			jdbc.update("UPDATE wishlist_items SET sort_order = ?, updated_at = now() WHERE id = ?", order++, id);
		}
	}

	/**
	 * Flips an item to FULFILLED once it is covered — by the units sponsored, or by the money given
	 * towards its cost. Called by every road a gift can arrive on: an online sponsorship (E7-S6), a
	 * part-payment towards the cost, and cash handed over at the office.
	 *
	 * <p>Both arms are needed. A temple that is given the whole price of a grinder in ₹500 pieces has
	 * been given a grinder, and until the item is FULFILLED it never enters the E7-S5 lifecycle: the
	 * kitchen sees nothing to buy, and the daily archive sweep never takes it off the list. The unit
	 * arm stays because a sponsorship's amount is snapshotted at checkout, so a price raised in
	 * between would leave a devotee who bought the whole thing short of the new cost.
	 */
	@Transactional
	public void markFulfilledIfComplete(UUID itemId) {
		jdbc.update("""
				UPDATE wishlist_items i SET status = 'FULFILLED', fulfilled_at = now(), updated_at = now()
				WHERE i.id = ? AND i.status = 'ACTIVE'
				  AND (i.quantity_wanted <= COALESCE(
							(SELECT SUM(d.wishlist_quantity) FROM donations d
							 WHERE d.wishlist_item_id = i.id AND d.status = 'COMPLETED'), 0)
					OR i.price_inr * i.quantity_wanted <= COALESCE(
							(SELECT SUM(d.amount_inr) FROM donations d
							 WHERE d.wishlist_item_id = i.id AND d.status = 'COMPLETED'), 0))
				""", itemId);
	}

	/** Named sponsors of an item for public recognition (E7-S6) — anonymous gifts are never listed. */
	@Transactional(readOnly = true)
	public List<String> publicSponsors(UUID itemId) {
		return jdbc.query("""
				SELECT DISTINCT donor_name FROM donations
				WHERE wishlist_item_id = ? AND status = 'COMPLETED' AND is_anonymous = false
				  AND donor_name IS NOT NULL
				ORDER BY donor_name
				""", (rs, n) -> rs.getString("donor_name"), itemId);
	}

	/** Units already sponsored (COMPLETED) for an item — used by E7-S6's oversubscription guard. */
	@Transactional(readOnly = true)
	public int sponsoredUnits(UUID itemId) {
		Integer n = jdbc.queryForObject("""
				SELECT COALESCE(SUM(wishlist_quantity), 0) FROM donations
				WHERE wishlist_item_id = ? AND status = 'COMPLETED'
				""", Integer.class, itemId);
		return n == null ? 0 : n;
	}

	/** Archives FULFILLED items past the tenant's visibility window (E7-S5 sweep). */
	@Transactional
	public int archiveFulfilledForCurrentTenant() {
		int days = jdbc.query("SELECT wishlist_fulfilled_visible_days FROM tenant_settings",
				(rs, n) -> rs.getInt("wishlist_fulfilled_visible_days")).stream().findFirst().orElse(7);
		return jdbc.update("""
				UPDATE wishlist_items SET status = 'ARCHIVED', updated_at = now()
				WHERE status = 'FULFILLED' AND fulfilled_at IS NOT NULL
				  AND fulfilled_at < now() - (interval '1 day' * ?)
				""", days);
	}

	// ---------------------------------------------------------------------

	private Optional<WishlistItemView> findItem(UUID id) {
		return jdbc.query(SELECT + " WHERE i.id = ?", MAPPER, id).stream().findFirst();
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("wishlistItemId", id));
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static final String SELECT = """
			SELECT i.id, i.title, i.description, i.image_ref, i.price_inr, i.category, i.quantity_wanted,
				   i.sort_order, i.status, i.note, i.created_at,
				   COALESCE((SELECT SUM(d.wishlist_quantity) FROM donations d
						WHERE d.wishlist_item_id = i.id AND d.status = 'COMPLETED'), 0) AS sponsored,
				   -- What has actually been given towards this item, in rupees. A devotee may put any
				   -- amount towards a grinder rather than buying a whole one, so progress is money
				   -- rather than a count of units.
				   COALESCE((SELECT SUM(d.amount_inr) FROM donations d
						WHERE d.wishlist_item_id = i.id AND d.status = 'COMPLETED'), 0) AS paid_inr
			FROM wishlist_items i
			""";

	private static final RowMapper<WishlistItemView> MAPPER = (rs, n) -> new WishlistItemView(
			rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"),
			rs.getString("image_ref"), rs.getBigDecimal("price_inr"), rs.getString("category"),
			rs.getInt("quantity_wanted"), rs.getInt("sponsored"), rs.getBigDecimal("paid_inr"),
			rs.getInt("sort_order"), rs.getString("status"), rs.getString("note"),
			instant(rs.getObject("created_at", OffsetDateTime.class)));

	private static java.time.Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
