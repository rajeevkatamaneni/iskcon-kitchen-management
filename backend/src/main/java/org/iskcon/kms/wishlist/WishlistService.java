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
 * Wish-list items (E7-S5): admin CRUD, manual public ordering, and the fulfilment lifecycle. An item
 * is owed its price times the quantity wanted, and when the money given towards it (E7-S6) reaches
 * that the item flips FULFILLED; it stays visible for a tenant-configured window, then auto-archives.
 * Archived items vanish publicly but stay in ledger history.
 *
 * <p>Progress is money, and only money. A devotee does not buy a whole grinder or half of one — the
 * temple buys the grinder, and a gift is however many rupees of its price somebody could give.
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

	/**
	 * What a devotee sees when they come to give: ACTIVE items and, briefly, the ones just
	 * fulfilled, in the order the temple arranged them (E7-S6).
	 *
	 * <p>Called "public" until 2026-08-29, when giving stopped being possible without an account.
	 * The name was the last thing describing it — the list itself never was public, only reachable
	 * from a page that was. It differs from {@link #list} in what it hides, not in who may see it:
	 * an archived item is one the temple has stopped hoping for, and offering it would take money
	 * for something nobody is going to buy.
	 */
	@Transactional(readOnly = true)
	public List<WishlistItemView> forGiving() {
		return jdbc.query(SELECT + " WHERE i.status IN ('ACTIVE', 'FULFILLED') ORDER BY i.sort_order, i.created_at",
				MAPPER);
	}

	@Transactional(readOnly = true)
	public WishlistItemView get(UUID id) {
		return findItem(id).orElseThrow(() -> notFound(id));
	}

	/**
	 * The three kinds a wish-list item can be. The database has enforced this since V41 and nothing
	 * in front of it did, so an unrecognised value reached the CHECK constraint and came back as
	 * KMS-5001, "Something went wrong at our end" — for a plain bad input, with no field named.
	 * Found while a temple was being seeded on 2026-08-19.
	 */
	private static final java.util.Set<String> CATEGORIES =
			java.util.Set.of("CONSUMABLE", "EQUIPMENT", "OTHER");

	private static String requireKnownCategory(String category) {
		String value = category == null ? "" : category.trim().toUpperCase(java.util.Locale.ROOT);
		if (!CATEGORIES.contains(value)) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", "category",
					"reason", "choose one of: consumable, equipment, other"));
		}
		return value;
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateWishlistItemRequest request) {
		String category = requireKnownCategory(request.category());
		UUID id = UUID.randomUUID();
		Integer nextSort = jdbc.queryForObject(
				"SELECT COALESCE(MAX(sort_order), 0) + 1 FROM wishlist_items", Integer.class);
		jdbc.update("""
				INSERT INTO wishlist_items (id, tenant_id, title, description, image_ref, price_inr,
					category, quantity_wanted, sort_order, note, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, request.title().trim(), trimToNull(request.description()), trimToNull(request.imageRef()),
				request.priceInr(), category, request.quantityWanted(), nextSort,
				trimToNull(request.note()), actor.getUserId());
		return id;
	}

	@Transactional
	public void update(UUID id, UpdateWishlistItemRequest request) {
		String category = requireKnownCategory(request.category());
		int updated = jdbc.update("""
				UPDATE wishlist_items SET title = ?, description = ?, image_ref = ?, price_inr = ?,
					category = ?, quantity_wanted = ?, note = ?, updated_at = now() WHERE id = ?
				""", request.title().trim(), trimToNull(request.description()), trimToNull(request.imageRef()),
				request.priceInr(), category, request.quantityWanted(), trimToNull(request.note()), id);
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
	 * Flips an item to FULFILLED once the money given towards it covers its cost. Called by every
	 * road a gift can arrive on: an online gift towards the item (E7-S6), and cash handed over at
	 * the office.
	 *
	 * <p>A temple that is given the whole price of a grinder in ₹500 pieces has been given a
	 * grinder, and until the item is FULFILLED it never enters the E7-S5 lifecycle: the kitchen sees
	 * nothing to buy, and the daily archive sweep never takes it off the list.
	 */
	@Transactional
	public void markFulfilledIfComplete(UUID itemId) {
		jdbc.update("""
				UPDATE wishlist_items i SET status = 'FULFILLED', fulfilled_at = now(), updated_at = now()
				WHERE i.id = ? AND i.status = 'ACTIVE'
				  AND i.price_inr * i.quantity_wanted <= COALESCE(
						(SELECT SUM(d.amount_inr) FROM donations d
						 WHERE d.wishlist_item_id = i.id AND d.status = 'COMPLETED'), 0)
				""", itemId);
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
			rs.getInt("quantity_wanted"), rs.getBigDecimal("paid_inr"),
			rs.getInt("sort_order"), rs.getString("status"), rs.getString("note"),
			instant(rs.getObject("created_at", OffsetDateTime.class)));

	private static java.time.Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
