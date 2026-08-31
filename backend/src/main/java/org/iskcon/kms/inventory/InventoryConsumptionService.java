package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drawing stock down to cook a meal (E3-S6). The meal planner (E4) owns the moment; this is the
 * service it calls.
 *
 * <p>A recipe scaled to a target yield (E2-S3) becomes a set of ingredient requirements. Each is met
 * first-expiry-first from the batches on hand — the cook may pin a particular batch to the front —
 * and each draw becomes a negative {@code CONSUMPTION} movement referencing the meal plan. Stock is
 * only ever reduced through the ledger, so the numbers stay honest.
 *
 * <p>Two guarantees. <strong>Preview before commit:</strong> {@link #preview} works out the whole
 * drawdown and reports any shortfalls without writing a thing, so the planner can show the cook what
 * will be used and whether there is enough. <strong>All or nothing:</strong> {@link #consume}
 * computes the full plan first and, if any ingredient is short, refuses before writing a single
 * movement — a half-cooked drawdown that consumed the dal but not the rice would corrupt both stock
 * figures.
 */
@Service
public class InventoryConsumptionService {

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final StockMovementService stockMovementService;

	public InventoryConsumptionService(
			JdbcTemplate jdbc, RecipeService recipeService, StockMovementService stockMovementService) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.stockMovementService = stockMovementService;
	}

	/** Works out the drawdown and any shortfalls without writing anything. */
	@Transactional(readOnly = true)
	public ConsumptionPlan preview(UUID recipeId, BigDecimal targetYield, List<BatchOverride> overrides) {
		Plan plan = computePlan(recipeId, targetYield, overrides);
		return new ConsumptionPlan(
				plan.recipeId(), plan.recipeName(), plan.targetYield(), plan.yieldUnit(),
				plan.sufficient(), toPlannedLines(plan.lines(), null), plan.shortfalls());
	}

	/** Commits the consumption, or refuses in full if anything is short. */
	@Transactional
	public ConsumptionPlan consume(AuthenticatedUser actor, ConsumeRequest request) {
		Plan plan = computePlan(request.recipeId(), request.targetYield(), request.batchOverrides());
		if (!plan.sufficient()) {
			throw new ApplicationException(ErrorCode.INSUFFICIENT_STOCK, Map.of(
					"recipeId", request.recipeId(),
					"shortfalls", plan.shortfalls().stream()
							.map(s -> "%s: need %s, have %s %s".formatted(
									s.ingredientName(), s.required(), s.available(), s.unit()))
							.toList()));
		}

		MovementReference referenceType = request.mealPlanId() == null ? null : MovementReference.MEAL_PLAN;
		String note = trimToNull(request.note());
		Map<UUID, List<PlannedDraw>> committedDraws = new LinkedHashMap<>();

		for (InternalLine line : plan.lines()) {
			Unit base = InventoryUnits.baseUnit(line.canonicalUnit().family());
			List<PlannedDraw> draws = new ArrayList<>();
			for (InternalDraw draw : line.draws()) {
				BigDecimal takeBase = draw.takeBase().setScale(3, java.math.RoundingMode.HALF_UP);
				UUID movementId = stockMovementService.record(actor, new RecordMovement(
						line.ingredientId(), null, draw.batchId(),
						takeBase.negate(), base, MovementType.CONSUMPTION,
						null, null, null, referenceType, request.mealPlanId(), note));
				draws.add(new PlannedDraw(draw.batchId(),
						InventoryUnits.fromBase(draw.takeBase(), line.canonicalUnit()),
						line.canonicalUnit().name(), draw.expiry(), movementId));
			}
			committedDraws.put(line.ingredientId(), draws);
		}

		return new ConsumptionPlan(
				plan.recipeId(), plan.recipeName(), plan.targetYield(), plan.yieldUnit(),
				true, toPlannedLines(plan.lines(), committedDraws), List.of());
	}

	// ---------------------------------------------------------------------

	private Plan computePlan(UUID recipeId, BigDecimal targetYield, List<BatchOverride> overrides) {
		// RLS-scoped: a recipe in another temple simply isn't found.
		ScaledRecipeView scaled = recipeService.scale(recipeId, targetYield);

		Map<UUID, UUID> overrideBatch = new LinkedHashMap<>();
		if (overrides != null) {
			for (BatchOverride o : overrides) {
				overrideBatch.put(o.ingredientId(), o.batchId());
			}
		}

		// Aggregate requirement per ingredient: a recipe may list one more than once, and each must
		// draw from the shared batch pool, not see the full stock independently.
		Map<UUID, BigDecimal> requiredBase = new LinkedHashMap<>();
		Map<UUID, String> names = new LinkedHashMap<>();
		for (ScaledLine line : scaled.ingredients()) {
			Unit unit = Unit.valueOf(line.rawUnit());
			requiredBase.merge(line.ingredientId(), InventoryUnits.toBase(line.rawQuantity(), unit), BigDecimal::add);
			names.putIfAbsent(line.ingredientId(), line.ingredientName());
		}

		List<UUID> ingredientIds = List.copyOf(requiredBase.keySet());
		Map<UUID, Unit> canonicalUnits = loadCanonicalUnits(ingredientIds);
		Map<UUID, List<BatchAgg>> batches = loadPositiveBatches(ingredientIds);

		List<InternalLine> lines = new ArrayList<>();
		List<StockShortfall> shortfalls = new ArrayList<>();

		for (UUID ingredientId : ingredientIds) {
			BigDecimal needed = requiredBase.get(ingredientId);
			Unit canonical = canonicalUnits.get(ingredientId);
			List<BatchAgg> available = orderForDraw(
					new ArrayList<>(batches.getOrDefault(ingredientId, List.of())),
					overrideBatch.get(ingredientId), names.get(ingredientId));

			BigDecimal availableBase = available.stream()
					.map(BatchAgg::qtyBase).reduce(BigDecimal.ZERO, BigDecimal::add);

			List<InternalDraw> draws = new ArrayList<>();
			BigDecimal remaining = needed;
			for (BatchAgg batch : available) {
				if (remaining.signum() <= 0) {
					break;
				}
				BigDecimal take = batch.qtyBase().min(remaining);
				draws.add(new InternalDraw(batch.batchId(), take, batch.expiry()));
				remaining = remaining.subtract(take);
			}

			if (remaining.signum() > 0) {
				shortfalls.add(new StockShortfall(ingredientId, names.get(ingredientId),
						InventoryUnits.fromBase(needed, canonical),
						InventoryUnits.fromBase(availableBase, canonical), canonical.name()));
			}
			lines.add(new InternalLine(ingredientId, names.get(ingredientId), canonical, needed, draws));
		}

		return new Plan(scaled.id(), scaled.name(), targetYield, scaled.baseYieldUnit(),
				shortfalls.isEmpty(), lines, shortfalls);
	}

	/** FEFO order, with an optional overridden batch pulled to the front. */
	private List<BatchAgg> orderForDraw(List<BatchAgg> batches, UUID override, String ingredientName) {
		if (override != null && batches.stream().noneMatch(b -> b.batchId().equals(override))) {
			// The cook named a batch that holds nothing (or isn't this ingredient's) — say so rather
			// than silently ignoring the choice.
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "batchOverrides", "ingredient", ingredientName, "batchId", override));
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

	private List<PlannedLine> toPlannedLines(List<InternalLine> lines, Map<UUID, List<PlannedDraw>> committed) {
		List<PlannedLine> result = new ArrayList<>();
		for (InternalLine line : lines) {
			List<PlannedDraw> draws = committed != null
					? committed.getOrDefault(line.ingredientId(), List.of())
					: line.draws().stream()
							.map(d -> new PlannedDraw(d.batchId(),
									InventoryUnits.fromBase(d.takeBase(), line.canonicalUnit()),
									line.canonicalUnit().name(), d.expiry(), null))
							.toList();
			result.add(new PlannedLine(line.ingredientId(), line.name(),
					InventoryUnits.fromBase(line.requiredBase(), line.canonicalUnit()),
					line.canonicalUnit().name(), draws));
		}
		return result;
	}

	private static String placeholders(List<UUID> ids) {
		return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	// FEFO: nearest expiry first, no-expiry last, then oldest received first.
	private static final Comparator<BatchAgg> FEFO = Comparator
			.comparing(BatchAgg::expiry, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(BatchAgg::received, Comparator.nullsLast(Comparator.naturalOrder()));

	private record Plan(
			UUID recipeId, String recipeName, BigDecimal targetYield, String yieldUnit,
			boolean sufficient, List<InternalLine> lines, List<StockShortfall> shortfalls) {
	}

	private record InternalLine(
			UUID ingredientId, String name, Unit canonicalUnit, BigDecimal requiredBase, List<InternalDraw> draws) {
	}

	private record InternalDraw(UUID batchId, BigDecimal takeBase, LocalDate expiry) {
	}

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
