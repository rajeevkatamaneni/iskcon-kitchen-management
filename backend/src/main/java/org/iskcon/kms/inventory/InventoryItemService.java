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
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
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

	// A manual adjustment moving more than this fraction of what's on hand needs a Temple Admin.
	private static final BigDecimal LARGE_ADJUSTMENT_FRACTION = new BigDecimal("0.20");

	// "Today" for expiry is the temple's today. India-first, so a batch is "expiring soon" against
	// the Indian calendar day, not the server's UTC one (matching DocumentGenerationService).
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final StockMovementService stockMovementService;

	public InventoryItemService(
			JdbcTemplate jdbc, AuditService auditService, StockMovementService stockMovementService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.stockMovementService = stockMovementService;
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

	// ---- Manual adjustment (E3-S7) ---------------------------------------

	/**
	 * Corrects one batch's stock by a signed amount, recording it as an {@code ADJUSTMENT} movement —
	 * the only way stock changes outside a receipt, donation, or consumption.
	 *
	 * <p>Three guards, all here rather than in the controller because they depend on the current
	 * stock: an adjustment may not take a batch below zero (you cannot spoil more than you hold); a
	 * <em>large</em> one — over {@value #LARGE_ADJUSTMENT_FRACTION} of what's on hand — needs a Temple
	 * Admin, so a big write-off is a leadership decision, not a floor one; and a large adjustment is
	 * additionally written to the audit log, since routine small corrections live in the ledger alone
	 * but an unusual one is exactly what a review looks for.
	 */
	@Transactional
	public UUID adjust(AuthenticatedUser actor, UUID itemId, AdjustStockRequest request) {
		ItemRow item = jdbc.query(ITEM_SELECT + " WHERE ii.id = ?", ITEM_MAPPER, itemId)
				.stream().findFirst().orElseThrow(() -> notFound(itemId));
		Unit canonical = Unit.valueOf(item.canonicalUnit());
		Unit unit = parseUnit(request.unit());
		if (unit.family() != canonical.family()) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", request.unit()));
		}
		if (request.quantity() == null || request.quantity().signum() == 0) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "quantity"));
		}
		if (request.reason() == AdjustmentReason.OTHER
				&& (request.note() == null || request.note().isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "note"));
		}

		BigDecimal batchBase = batchStockBase(item.ingredientId(), request.batchId());
		if (batchBase == null) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("batchId", request.batchId()));
		}
		BigDecimal deltaBase = request.quantity().multiply(BigDecimal.valueOf(unit.baseFactor()));
		BigDecimal newBatchBase = batchBase.add(deltaBase);
		if (newBatchBase.signum() < 0) {
			throw new ApplicationException(ErrorCode.STOCK_WOULD_GO_NEGATIVE, Map.of(
					"batchId", request.batchId(),
					"available", toCanonical(batchBase, canonical),
					"unit", canonical.name()));
		}

		boolean large = isLargeAdjustment(deltaBase, onHandBase(item.ingredientId()));
		if (large && !RolePermissions.has(actor.getRole(), Permission.APPROVE_LARGE_STOCK_ADJUSTMENT)) {
			throw new ApplicationException(ErrorCode.ADJUSTMENT_REQUIRES_ADMIN, Map.of("inventoryItemId", itemId));
		}

		UUID movementId = stockMovementService.record(actor, new RecordMovement(
				item.ingredientId(), item.storageLocation(), request.batchId(),
				request.quantity(), unit, MovementType.ADJUSTMENT,
				null, null, request.reason(), null, null, trimToNull(request.note())));

		if (large) {
			Map<String, Object> before = new LinkedHashMap<>();
			before.put("ingredient", item.ingredientName());
			before.put("batchId", request.batchId());
			before.put("batchStock", toCanonical(batchBase, canonical));
			before.put("unit", canonical.name());
			Map<String, Object> after = new LinkedHashMap<>();
			after.put("ingredient", item.ingredientName());
			after.put("batchId", request.batchId());
			after.put("batchStock", toCanonical(newBatchBase, canonical));
			after.put("unit", canonical.name());
			after.put("reason", request.reason().name());
			after.put("delta", request.quantity());
			auditService.record(actor, AuditAction.STOCK_ADJUSTED, AuditEntityType.INVENTORY_ITEM, itemId,
					before, after, trimToNull(request.note()));
		}
		return movementId;
	}

	// ---------------------------------------------------------------------

	/** A single batch's stock in base units, or null if the batch has no movements (doesn't exist). */
	private BigDecimal batchStockBase(UUID ingredientId, UUID batchId) {
		return jdbc.queryForObject("""
				SELECT SUM(quantity * CASE unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END)
				FROM stock_movements WHERE ingredient_id = ? AND batch_id = ?
				""", BigDecimal.class, ingredientId, batchId);
	}

	/** A consumable's total stock in base units (zero if none). */
	private BigDecimal onHandBase(UUID ingredientId) {
		BigDecimal value = jdbc.queryForObject("""
				SELECT COALESCE(SUM(quantity * CASE unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
		return value == null ? BigDecimal.ZERO : value;
	}

	private boolean isLargeAdjustment(BigDecimal deltaBase, BigDecimal onHandBase) {
		// Adjusting an item that holds nothing (or is already negative) can't be sized as a fraction,
		// so it always counts as large — an unusual case that deserves a second signature.
		if (onHandBase.signum() <= 0) {
			return true;
		}
		return deltaBase.abs().divide(onHandBase, 4, RoundingMode.HALF_UP)
				.compareTo(LARGE_ADJUSTMENT_FRACTION) > 0;
	}

	private Unit parseUnit(String unit) {
		try {
			return Unit.valueOf(unit);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", String.valueOf(unit)));
		}
	}

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
