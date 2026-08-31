package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * First-expiry-first-out: which batches a requirement should come out of, and whether there are
 * enough of them.
 *
 * <p><strong>This is the only FEFO in the application, and it is here because it is now asked twice.</strong>
 * It was written inside {@link InventoryConsumptionService} for cooking a meal (E3-S6). Issuing to
 * one of the temple's other kitchens (E10-S7) needs the same answer to the same question — take the
 * lot that goes off first, tell me if there is not enough — and a second copy of a rule about which
 * food gets used before it spoils is a rule that will drift. So the shared half was lifted out
 * unchanged and both callers now come here. Nothing about the ordering, the batch override or the
 * all-or-nothing shape was altered in the move.
 *
 * <p><strong>What it does not do.</strong> It reads the ledger and it computes; it writes nothing.
 * Its two callers each hold their own transaction and their own reason for a movement, and both
 * refuse in full before writing a single row if any line is short. Keeping the decision separate
 * from the writing is what makes "all or nothing" expressible at all.
 *
 * <p><strong>Stock is derived, never stored.</strong> A batch's remaining quantity is the sum of its
 * movements, so this reads {@code stock_movements} rather than any balance column, and only batches
 * summing to more than zero are candidates. The read runs under RLS like every other, so another
 * temple's stock is not merely filtered out — it is not visible.
 */
@Service
public class FefoAllocator {

	private final JdbcTemplate jdbc;

	public FefoAllocator(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Works out where each requirement comes from.
	 *
	 * @param requiredBase  how much of each ingredient is wanted, in its family's base unit. Its
	 *                      iteration order is the order the lines come back in, so pass a map that
	 *                      keeps one — the caller's own line order is what a person reads
	 * @param names         each ingredient's name, for the shortfall a person is shown
	 * @param overrideBatch a batch pinned to the front of the queue for an ingredient, or nothing.
	 *                      Naming a batch that holds none of that ingredient is refused rather than
	 *                      quietly ignored: somebody chose it on purpose
	 */
	public StockAllocation allocate(
			Map<UUID, BigDecimal> requiredBase, Map<UUID, String> names, Map<UUID, UUID> overrideBatch) {

		List<UUID> ingredientIds = List.copyOf(requiredBase.keySet());
		Map<UUID, Unit> canonicalUnits = loadCanonicalUnits(ingredientIds);
		Map<UUID, List<BatchAgg>> batches = loadPositiveBatches(ingredientIds);

		List<AllocatedLine> lines = new ArrayList<>();
		List<StockShortfall> shortfalls = new ArrayList<>();

		for (UUID ingredientId : ingredientIds) {
			BigDecimal needed = requiredBase.get(ingredientId);
			Unit canonical = canonicalUnits.get(ingredientId);
			String name = names.get(ingredientId);
			List<BatchAgg> available = orderForDraw(
					new ArrayList<>(batches.getOrDefault(ingredientId, List.of())),
					overrideBatch == null ? null : overrideBatch.get(ingredientId), name);

			BigDecimal availableBase = available.stream()
					.map(BatchAgg::qtyBase).reduce(BigDecimal.ZERO, BigDecimal::add);

			List<BatchDraw> draws = new ArrayList<>();
			BigDecimal remaining = needed;
			for (BatchAgg batch : available) {
				if (remaining.signum() <= 0) {
					break;
				}
				BigDecimal take = batch.qtyBase().min(remaining);
				draws.add(new BatchDraw(batch.batchId(), take, batch.expiry()));
				remaining = remaining.subtract(take);
			}

			if (remaining.signum() > 0) {
				shortfalls.add(new StockShortfall(ingredientId, name,
						InventoryUnits.fromBase(needed, canonical),
						InventoryUnits.fromBase(availableBase, canonical), canonical.name()));
			}
			lines.add(new AllocatedLine(ingredientId, name, canonical, needed, draws));
		}

		return new StockAllocation(lines, shortfalls);
	}

	// ---------------------------------------------------------------------

	/** FEFO order, with an optional overridden batch pulled to the front. */
	private List<BatchAgg> orderForDraw(List<BatchAgg> batches, UUID override, String ingredientName) {
		if (override != null && batches.stream().noneMatch(b -> b.batchId().equals(override))) {
			// The cook named a batch that holds nothing (or isn't this ingredient's) — say so rather
			// than silently ignoring the choice.
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "batchOverrides", "ingredient", String.valueOf(ingredientName),
							"batchId", override));
		}
		batches.sort(Comparator
				.comparingInt((BatchAgg b) -> b.batchId().equals(override) ? 0 : 1)
				.thenComparing(FEFO));
		return batches;
	}

	private Map<UUID, Unit> loadCanonicalUnits(List<UUID> ingredientIds) {
		Map<UUID, Unit> units = new LinkedHashMap<>();
		if (ingredientIds.isEmpty()) {
			return units;
		}
		jdbc.query("SELECT id, canonical_unit FROM ingredients WHERE id IN (" + placeholders(ingredientIds) + ")",
				rs -> {
					units.put(rs.getObject("id", UUID.class), Unit.valueOf(rs.getString("canonical_unit")));
				}, ingredientIds.toArray());
		return units;
	}

	private Map<UUID, List<BatchAgg>> loadPositiveBatches(List<UUID> ingredientIds) {
		Map<UUID, List<BatchAgg>> byIngredient = new LinkedHashMap<>();
		if (ingredientIds.isEmpty()) {
			return byIngredient;
		}
		List<BatchAgg> rows = jdbc.query("""
				SELECT m.ingredient_id, m.batch_id,
					   SUM(to_base_qty(m.quantity, m.unit))
						   AS qty_base,
					   MAX(m.expiry_date)   AS expiry_date,
					   MAX(m.received_date) AS received_date
				FROM stock_movements m
				WHERE m.ingredient_id IN (""" + placeholders(ingredientIds) + """
				)
				GROUP BY m.ingredient_id, m.batch_id
				HAVING SUM(to_base_qty(m.quantity, m.unit)) > 0
				""", BATCH_MAPPER, ingredientIds.toArray());
		for (BatchAgg row : rows) {
			byIngredient.computeIfAbsent(row.ingredientId(), k -> new ArrayList<>()).add(row);
		}
		return byIngredient;
	}

	private static String placeholders(List<UUID> ids) {
		return String.join(", ", Collections.nCopies(ids.size(), "?"));
	}

	// FEFO: nearest expiry first, no-expiry last, then oldest received first.
	private static final Comparator<BatchAgg> FEFO = Comparator
			.comparing(BatchAgg::expiry, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(BatchAgg::received, Comparator.nullsLast(Comparator.naturalOrder()));

	private record BatchAgg(
			UUID ingredientId, UUID batchId, BigDecimal qtyBase, LocalDate expiry, LocalDate received) {
	}

	private static final RowMapper<BatchAgg> BATCH_MAPPER = (rs, n) -> new BatchAgg(
			rs.getObject("ingredient_id", UUID.class),
			rs.getObject("batch_id", UUID.class),
			rs.getBigDecimal("qty_base"),
			rs.getObject("expiry_date", LocalDate.class),
			rs.getObject("received_date", LocalDate.class));
}
