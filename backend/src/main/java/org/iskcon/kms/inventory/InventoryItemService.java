package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumable inventory items and the stock view derived over them (E3-S1).
 *
 * <p><strong>Stock is never stored, only computed.</strong> An {@link InventoryItemService} row
 * carries where a consumable lives and when to reorder it — nothing else. How much is on hand is the
 * sum of that ingredient's {@link StockMovementService} ledger rows, worked out here at read time.
 * There is deliberately no endpoint that sets a stock level: the only way stock changes is a
 * movement, so the ledger and the displayed figure can never disagree.
 *
 * <p>Movements may be recorded in any unit of the ingredient's family (a sack received in KG, a
 * spoonful consumed in GM), so quantities are summed in base units — grams, millilitres, pieces —
 * and presented back in the ingredient's canonical unit. Batches are shown first-expiry-first, and
 * two badges are computed for the list: below the reorder threshold, and holding stock that expires
 * within the caller's window (7 days by default).
 */
@Service
public class InventoryItemService {

	private static final int DEFAULT_EXPIRY_WINDOW_DAYS = 7;

	// "Today" for expiry is the temple's today. India-first, so a batch is "expiring soon" against
	// the Indian calendar day, not the server's UTC one (matching DocumentGenerationService).
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public InventoryItemService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	// ---- Stock view ------------------------------------------------------

	/** The stock list, optionally filtered by storage location and/or ingredient category. */
	@Transactional(readOnly = true)
	public List<StockItemView> list(String location, String category, Integer expiringWithinDays) {
		LocalDate horizon = horizon(expiringWithinDays);

		StringBuilder sql = new StringBuilder(ITEM_SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (location != null && !location.isBlank()) {
			sql.append(" AND ii.storage_location = ?");
			args.add(location.trim());
		}
		if (category != null && !category.isBlank()) {
			sql.append(" AND i.category = ?");
			args.add(category.trim());
		}
		sql.append(" ORDER BY i.name");

		List<ItemRow> items = jdbc.query(sql.toString(), ITEM_MAPPER, args.toArray());
		Map<UUID, List<BatchAgg>> batchesByIngredient = loadBatches(null);

		return items.stream()
				.map(item -> toItemView(item, batchesByIngredient.getOrDefault(item.ingredientId(), List.of()), horizon))
				.toList();
	}

	/** One consumable with its stock broken out by batch, FEFO-ordered. */
	@Transactional(readOnly = true)
	public StockDetailView get(UUID itemId, Integer expiringWithinDays) {
		LocalDate horizon = horizon(expiringWithinDays);
		ItemRow item = jdbc.query(ITEM_SELECT + " WHERE ii.id = ?", ITEM_MAPPER, itemId)
				.stream().findFirst().orElseThrow(() -> notFound(itemId));

		List<BatchAgg> aggs = loadBatches(item.ingredientId()).getOrDefault(item.ingredientId(), List.of());
		Unit unit = Unit.valueOf(item.canonicalUnit());

		List<BatchStock> batches = aggs.stream()
				.filter(a -> a.qtyBase().signum() != 0)
				.sorted(FEFO)
				.map(a -> new BatchStock(
						a.batchId(),
						toCanonical(a.qtyBase(), unit),
						item.canonicalUnit(),
						a.expiryDate(),
						a.receivedDate(),
						isExpiringSoon(a, horizon)))
				.toList();

		return new StockDetailView(toItemView(item, aggs, horizon), batches);
	}

	// ---- Item management -------------------------------------------------

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateInventoryItemRequest request) {
		// RLS-scoped existence check: the ingredient must be this tenant's. A foreign key alone would
		// not close this — FK validation runs as the table owner and does not see RLS — so a stray
		// ingredient_id from another temple could otherwise be referenced.
		String ingredientName = findIngredientName(request.ingredientId())
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", request.ingredientId())));

		UUID id = UUID.randomUUID();
		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO inventory_items (
							id, tenant_id, ingredient_id, storage_location, reorder_threshold, notes)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setObject(2, request.ingredientId());
				ps.setString(3, trimToNull(request.storageLocation()));
				ps.setBigDecimal(4, request.reorderThreshold());
				ps.setString(5, trimToNull(request.notes()));
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.INVENTORY_ITEM_ALREADY_EXISTS, Map.of("ingredientId", request.ingredientId()), e);
		}

		auditService.record(actor, AuditAction.INVENTORY_ITEM_ADDED, AuditEntityType.INVENTORY_ITEM, id,
				null, itemSnapshot(ingredientName, request.storageLocation(), request.reorderThreshold()), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID itemId, UpdateInventoryItemRequest request) {
		ItemRow before = jdbc.query(ITEM_SELECT + " WHERE ii.id = ?", ITEM_MAPPER, itemId)
				.stream().findFirst().orElseThrow(() -> notFound(itemId));

		jdbc.update("""
				UPDATE inventory_items
				SET storage_location = ?, reorder_threshold = ?, notes = ?, updated_at = now()
				WHERE id = ?
				""",
				trimToNull(request.storageLocation()), request.reorderThreshold(),
				trimToNull(request.notes()), itemId);

		auditService.record(actor, AuditAction.INVENTORY_ITEM_UPDATED, AuditEntityType.INVENTORY_ITEM, itemId,
				itemSnapshot(before.ingredientName(), before.storageLocation(), before.reorderThreshold()),
				itemSnapshot(before.ingredientName(), request.storageLocation(), request.reorderThreshold()),
				null);
	}

	@Transactional
	public void delete(AuthenticatedUser actor, UUID itemId) {
		ItemRow existing = jdbc.query(ITEM_SELECT + " WHERE ii.id = ?", ITEM_MAPPER, itemId)
				.stream().findFirst().orElseThrow(() -> notFound(itemId));

		// Metadata only — the movement history remains, so stopping tracking never erases the ledger.
		jdbc.update("DELETE FROM inventory_items WHERE id = ?", itemId);

		auditService.record(actor, AuditAction.INVENTORY_ITEM_REMOVED, AuditEntityType.INVENTORY_ITEM, itemId,
				itemSnapshot(existing.ingredientName(), existing.storageLocation(), existing.reorderThreshold()),
				null, null);
	}

	// ---------------------------------------------------------------------

	/** Batch aggregates keyed by ingredient. With a non-null id, only that ingredient's batches. */
	private Map<UUID, List<BatchAgg>> loadBatches(UUID ingredientId) {
		String sql = """
				SELECT m.ingredient_id, m.batch_id,
					   SUM(m.quantity * CASE m.unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END)
						   AS qty_base,
					   MAX(m.expiry_date)   AS expiry_date,
					   MAX(m.received_date) AS received_date
				FROM stock_movements m
				""";
		List<BatchAgg> rows;
		if (ingredientId == null) {
			rows = jdbc.query(sql + " GROUP BY m.ingredient_id, m.batch_id", BATCH_MAPPER);
		} else {
			rows = jdbc.query(sql + " WHERE m.ingredient_id = ? GROUP BY m.ingredient_id, m.batch_id",
					BATCH_MAPPER, ingredientId);
		}

		Map<UUID, List<BatchAgg>> byIngredient = new LinkedHashMap<>();
		for (BatchAgg row : rows) {
			byIngredient.computeIfAbsent(row.ingredientId(), k -> new ArrayList<>()).add(row);
		}
		return byIngredient;
	}

	private StockItemView toItemView(ItemRow item, List<BatchAgg> aggs, LocalDate horizon) {
		Unit unit = Unit.valueOf(item.canonicalUnit());

		BigDecimal onHandBase = aggs.stream()
				.map(BatchAgg::qtyBase)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal onHand = toCanonical(onHandBase, unit);

		LocalDate soonestExpiry = aggs.stream()
				.filter(a -> a.qtyBase().signum() > 0 && a.expiryDate() != null)
				.map(BatchAgg::expiryDate)
				.min(Comparator.naturalOrder())
				.orElse(null);

		boolean expiringSoon = aggs.stream().anyMatch(a -> isExpiringSoon(a, horizon));
		boolean belowThreshold = item.reorderThreshold() != null
				&& onHand.compareTo(item.reorderThreshold()) < 0;

		return new StockItemView(
				item.itemId(), item.ingredientId(), item.ingredientName(), item.category(),
				item.storageLocation(), item.canonicalUnit(), onHand, item.reorderThreshold(),
				belowThreshold, expiringSoon, soonestExpiry, item.notes());
	}

	private boolean isExpiringSoon(BatchAgg a, LocalDate horizon) {
		return a.qtyBase().signum() > 0
				&& a.expiryDate() != null
				&& !a.expiryDate().isAfter(horizon);
	}

	/**
	 * Base units back into the ingredient's canonical unit, normalised so the number reads cleanly:
	 * {@code 14}, not {@code 14.000}, and {@code 2.25} kept exact. Base factors are powers of ten, so
	 * the division never actually rounds.
	 */
	private BigDecimal toCanonical(BigDecimal base, Unit unit) {
		BigDecimal value = base.divide(BigDecimal.valueOf(unit.baseFactor()), 3, RoundingMode.HALF_UP);
		if (value.signum() == 0) {
			return BigDecimal.ZERO;
		}
		value = value.stripTrailingZeros();
		return value.scale() < 0 ? value.setScale(0) : value;
	}

	private LocalDate horizon(Integer expiringWithinDays) {
		int window = (expiringWithinDays == null || expiringWithinDays < 0)
				? DEFAULT_EXPIRY_WINDOW_DAYS : expiringWithinDays;
		return LocalDate.now(TEMPLE_ZONE).plusDays(window);
	}

	private Optional<String> findIngredientName(UUID ingredientId) {
		return jdbc.query("SELECT name FROM ingredients WHERE id = ?",
				(rs, n) -> rs.getString("name"), ingredientId).stream().findFirst();
	}

	private Map<String, Object> itemSnapshot(String ingredientName, String location, BigDecimal threshold) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("ingredient", ingredientName);
		s.put("storageLocation", trimToNull(location));
		s.put("reorderThreshold", threshold);
		return s;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("inventoryItemId", id));
	}

	// FEFO: nearest expiry first, nulls (no expiry) last, then oldest received first.
	private static final Comparator<BatchAgg> FEFO = Comparator
			.comparing(BatchAgg::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(BatchAgg::receivedDate, Comparator.nullsLast(Comparator.naturalOrder()));

	private static final String ITEM_SELECT = """
			SELECT ii.id AS item_id, ii.ingredient_id, i.name AS ingredient_name, i.category,
				   ii.storage_location, i.canonical_unit, ii.reorder_threshold, ii.notes
			FROM inventory_items ii
			JOIN ingredients i ON i.id = ii.ingredient_id
			""";

	private record ItemRow(
			UUID itemId, UUID ingredientId, String ingredientName, String category,
			String storageLocation, String canonicalUnit, BigDecimal reorderThreshold, String notes) {
	}

	private record BatchAgg(
			UUID ingredientId, UUID batchId, BigDecimal qtyBase, LocalDate expiryDate, LocalDate receivedDate) {
	}

	private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, n) -> new ItemRow(
			rs.getObject("item_id", UUID.class),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			rs.getString("category"),
			rs.getString("storage_location"),
			rs.getString("canonical_unit"),
			rs.getBigDecimal("reorder_threshold"),
			rs.getString("notes"));

	private static final RowMapper<BatchAgg> BATCH_MAPPER = (rs, n) -> new BatchAgg(
			rs.getObject("ingredient_id", UUID.class),
			rs.getObject("batch_id", UUID.class),
			rs.getBigDecimal("qty_base"),
			rs.getObject("expiry_date", LocalDate.class),
			rs.getObject("received_date", LocalDate.class));
}
