package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 *
 * <p><strong>The FEFO rule itself lives in {@link FefoAllocator} now, not here.</strong> Issuing to
 * one of the temple's other kitchens (E10-S7) asks the same question this does, and two copies of
 * "use the lot that spoils first" is one copy too many. What stayed behind is what is genuinely
 * about cooking: scaling the recipe, aggregating an ingredient a recipe names twice, and writing the
 * movements as {@code CONSUMPTION} against a meal plan.
 */
@Service
public class InventoryConsumptionService {

	private final RecipeService recipeService;
	private final StockMovementService stockMovementService;
	private final FefoAllocator fefoAllocator;

	public InventoryConsumptionService(
			RecipeService recipeService, StockMovementService stockMovementService,
			FefoAllocator fefoAllocator) {
		this.recipeService = recipeService;
		this.stockMovementService = stockMovementService;
		this.fefoAllocator = fefoAllocator;
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

		for (AllocatedLine line : plan.lines()) {
			Unit base = InventoryUnits.baseUnit(line.canonicalUnit().family());
			List<PlannedDraw> draws = new ArrayList<>();
			for (BatchDraw draw : line.draws()) {
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

		StockAllocation allocation = fefoAllocator.allocate(requiredBase, names, overrideBatch);

		return new Plan(scaled.id(), scaled.name(), targetYield, scaled.baseYieldUnit(),
				allocation.sufficient(), allocation.lines(), allocation.shortfalls());
	}

	private List<PlannedLine> toPlannedLines(List<AllocatedLine> lines, Map<UUID, List<PlannedDraw>> committed) {
		List<PlannedLine> result = new ArrayList<>();
		for (AllocatedLine line : lines) {
			List<PlannedDraw> draws = committed != null
					? committed.getOrDefault(line.ingredientId(), List.of())
					: line.draws().stream()
							.map(d -> new PlannedDraw(d.batchId(),
									InventoryUnits.fromBase(d.takeBase(), line.canonicalUnit()),
									line.canonicalUnit().name(), d.expiry(), null))
							.toList();
			result.add(new PlannedLine(line.ingredientId(), line.ingredientName(),
					InventoryUnits.fromBase(line.requiredBase(), line.canonicalUnit()),
					line.canonicalUnit().name(), draws));
		}
		return result;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record Plan(
			UUID recipeId, String recipeName, BigDecimal targetYield, String yieldUnit,
			boolean sufficient, List<AllocatedLine> lines, List<StockShortfall> shortfalls) {
	}
}
