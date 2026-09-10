package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
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
 * <p><strong>The two methods here are two different questions, and that is the whole shape of this
 * class (T-087).</strong> {@link #preview} asks <em>"is there enough to cook this?"</em> — it is the
 * planning question, it writes nothing, and it answers <em>no</em> when the answer is no.
 * {@link #consume} says <em>"this was cooked"</em> — it is the recording question, and a recording
 * is never refused on stock grounds, because the food is already made and the rice already left the
 * store.
 *
 * <p>It used to refuse, and the defect that came of it is worth keeping written down. Driven on
 * staging in September: recording a Dinner at 60 L of curd rice was refused, at 20 L refused again,
 * and accepted at 1 L. The meal record now says the temple served one litre of curd rice to 235
 * people. <strong>Refusing the record does not put the rice back — it moves the lie out of the
 * stock ledger and into the meal record, where it is far harder to find.</strong> What a shortfall
 * gets instead is {@link MovementType#USED_BEYOND_RECORDED_STOCK}: a named row in the ledger saying
 * which ingredient the paperwork is behind on — and one that <strong>subtracts nothing</strong>, so
 * the store room lands at zero rather than at minus forty kilos (T-122). See {@link #consume} for
 * the reasoning in full.
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

	/**
	 * Works out the drawdown and any shortfalls without writing anything.
	 *
	 * <p><strong>This is the planning half, and it still says no.</strong> {@code sufficient} comes
	 * back false and every short ingredient is itemised with what was needed and what is there, which
	 * is how a forecast gets argued with before anybody lights a stove. Nothing in T-087 touched it:
	 * the point of that change was to tell the two questions apart, not to stop asking one of them.
	 */
	@Transactional(readOnly = true)
	public ConsumptionPlan preview(UUID recipeId, BigDecimal targetYield, List<BatchOverride> overrides) {
		Plan plan = computePlan(recipeId, targetYield, overrides);
		return new ConsumptionPlan(
				plan.recipeId(), plan.recipeName(), plan.targetYield(), plan.yieldUnit(),
				plan.sufficient(), toPlannedLines(plan.lines(), null), plan.shortfalls());
	}

	/**
	 * Records what the kitchen cooked, and <strong>never refuses it on stock grounds</strong> (T-087).
	 *
	 * <p><strong>The rule, in Rajeev's words:</strong> <em>"There should NEVER be a situation where
	 * the food was cooked and our tool tells them NOPE you are lying, you didn't have the ingredients
	 * to cook that food."</em> Everything below follows from that one sentence.
	 *
	 * <p>This method used to compute the whole plan and, if any ingredient was short, throw
	 * {@code KMS-400042} before writing a single movement. That reads like prudence and is not.
	 * Every caller of this method is <em>recording</em> — the meal recording form, the correction
	 * form's re-draw, and the consumption endpoint whose own name is "commit" — so by the time the
	 * question is asked the food is made and the rice is gone. <strong>Refusing does not put the
	 * rice back. It moves the lie out of the stock ledger and into the meal record</strong>, which
	 * is the one place nobody reconciles: on staging, a Dinner of 60 L of curd rice was refused,
	 * 20 L was refused, and 1 L went through, so the record says 235 people were served one litre.
	 * The refusal's advice — <em>"cook a smaller quantity"</em> — was being given to a meal that had
	 * already happened.
	 *
	 * <p><strong>What happens instead, and it is option (b) of three that were put to him.</strong>
	 * Draw what the batches actually hold, then post whatever the kitchen used beyond that as its
	 * own named movement, {@link MovementType#USED_BEYOND_RECORDED_STOCK}. Not a bare negative
	 * balance, and his reasoning is worth keeping verbatim because the cheap option is
	 * indistinguishable from the right one until somebody has to find it six weeks later:
	 * <em>"Negative numbers get normalised and ignored; a named movement appears in a list somebody
	 * reads, and it says which ingredient's paperwork is behind."</em>
	 *
	 * <p><strong>And the shortfall subtracts nothing — on hand stops at zero (T-122).</strong> This
	 * is the half T-087 got wrong and shipped. It booked the shortfall as a negative movement, so an
	 * ingredient's total read minus forty kilos, and it argued that the impossible figure was the
	 * finding rather than the bug. Shown that on staging, Rajeev: <em>"That makes no sense. We
	 * should stop at 0. How does negative ingredients make any sense?"</em> The draw takes what
	 * actually exists — FEFO to the bottom of each lot and no further, so no batch goes negative and
	 * neither does their total — and the remainder is a record of a <em>discrepancy</em> rather than
	 * a movement of <em>stock</em>: it says "40 Kg used beyond recorded stock", and the ledger's own
	 * arithmetic knows not to count it ({@code to_on_hand_qty}, V116).
	 *
	 * <p>Nothing else about the row changes. The missing forty kilos still did not come from nowhere,
	 * somebody still has not recorded a delivery, and the row still names which ingredient and still
	 * hangs off the meal that found it. What it no longer does is answer that question with a number
	 * no shelf can hold.
	 *
	 * <p><strong>Available may still be negative, and must be left alone.</strong> Available is on
	 * hand minus what the saved plans have claimed, so a minus there means the temple has promised
	 * more than it holds — a different figure, a different rule, and a sentence worth saying.
	 *
	 * <p><strong>The shortfall carries the same reference as the draws</strong>, deliberately, and it
	 * is what makes a correction whole. {@code StockMovementService.compensateAllFor} finds
	 * everything standing against a {@code (reference_type, reference_id)} pair, so a meal corrected
	 * afterwards gives back its shortfall along with its draws. Filed under anything else it would be
	 * the one row a correction quietly walked past, and the correction would leave the temple short
	 * by exactly the amount nobody could see.
	 *
	 * <p><strong>What is still all-or-nothing:</strong> the transaction. Nothing here refuses, but
	 * the caller's transaction still covers the whole meal, so a failure for any other reason unwinds
	 * every draw and every shortfall together.
	 *
	 * <p>The planning question has not moved: {@link #preview} still answers it, still reports
	 * {@code sufficient = false}, and still itemises what is short. This method now answers it too,
	 * after the fact — the returned plan carries the shortfalls it had to book rather than an empty
	 * list, so a caller that wants to say something about them has the facts to say it with.
	 */
	@Transactional
	public ConsumptionPlan consume(AuthenticatedUser actor, ConsumeRequest request) {
		Plan plan = computePlan(request.recipeId(), request.targetYield(), request.batchOverrides());

		MovementReference referenceType = request.mealPlanId() == null ? null : MovementReference.MEAL_PLAN;
		String note = trimToNull(request.note());
		Map<UUID, List<PlannedDraw>> committedDraws = new LinkedHashMap<>();

		for (AllocatedLine line : plan.lines()) {
			Unit base = InventoryUnits.baseUnit(line.canonicalUnit().family());
			List<PlannedDraw> draws = new ArrayList<>();
			BigDecimal drawnBase = BigDecimal.ZERO;
			for (BatchDraw draw : line.draws()) {
				BigDecimal takeBase = draw.takeBase().setScale(3, java.math.RoundingMode.HALF_UP);
				UUID movementId = stockMovementService.record(actor, new RecordMovement(
						line.ingredientId(), null, draw.batchId(),
						takeBase.negate(), base, MovementType.CONSUMPTION,
						null, null, null, referenceType, request.mealPlanId(), note));
				draws.add(new PlannedDraw(draw.batchId(),
						InventoryUnits.fromBase(draw.takeBase(), line.canonicalUnit()),
						line.canonicalUnit().name(), draw.expiry(), movementId));
				drawnBase = drawnBase.add(draw.takeBase());
			}
			committedDraws.put(line.ingredientId(), draws);

			bookShortfall(actor, line, base, drawnBase, referenceType, request.mealPlanId(), note);
		}

		return new ConsumptionPlan(
				plan.recipeId(), plan.recipeName(), plan.targetYield(), plan.yieldUnit(),
				plan.sufficient(), toPlannedLines(plan.lines(), committedDraws), plan.shortfalls());
	}

	/**
	 * Books whatever the batches could not cover as a movement of its own, or writes nothing when
	 * they covered it all.
	 *
	 * <p>The arithmetic is the allocator's, not a second opinion: {@code requiredBase} is what the
	 * scaled recipe asked for and {@code drawnBase} is what {@link FefoAllocator} could find, so
	 * their difference is exactly the shortfall it reported, expressed in the family's base unit
	 * because that is the unit the draws beside it are written in.
	 *
	 * <p><strong>Rounded before it is tested, and tested before it is written.</strong> Each draw is
	 * written at three decimal places, so a requirement that lands a fraction of a milligram past
	 * what the shelf holds would otherwise book a shortfall of 0.0004 gm — a row that says the
	 * paperwork is behind when it is not, on the one list that is meant to be worth reading.
	 * {@code stock_movements_quantity_nonzero} would refuse it anyway, which would turn a rounding
	 * artefact into a refused recording: the very thing this task exists to stop.
	 *
	 * <p><strong>A fresh batch id, not the last lot drawn.</strong> This food did not come out of any
	 * lot the store room knows about — that is the whole claim the row is making — and hanging it on
	 * a real batch would say a real sack of rice held less than nothing. Its own id keeps every
	 * recorded lot at zero or above; and since T-122 that lot holds zero rather than minus forty
	 * kilos, so it no longer lists itself among the real ones on the item's screen as a bare UUID
	 * with a minus number and no dates.
	 *
	 * <p><strong>The quantity stays negative, and the row still moves nothing.</strong> Those are not
	 * in tension once the two are told apart: the sign is what the row <em>says</em> — forty kilos
	 * went out of this kitchen that the books cannot account for, which is how the movement list
	 * renders it and how a storekeeper reads it — while what it <em>does</em> to the shelf is decided
	 * by its kind, in {@code to_on_hand_qty}. Flipping it positive to match the arithmetic would make
	 * the one row on that list read like a delivery.
	 */
	private void bookShortfall(
			AuthenticatedUser actor, AllocatedLine line, Unit base, BigDecimal drawnBase,
			MovementReference referenceType, UUID referenceId, String note) {

		BigDecimal shortBase = line.requiredBase().subtract(drawnBase)
				.setScale(3, java.math.RoundingMode.HALF_UP);
		if (shortBase.signum() <= 0) {
			return;
		}

		stockMovementService.record(actor, new RecordMovement(
				line.ingredientId(), null, UUID.randomUUID(),
				shortBase.negate(), base, MovementType.USED_BEYOND_RECORDED_STOCK,
				null, null, null, referenceType, referenceId, shortfallNote(line, note)));
	}

	/**
	 * What the row says to whoever finds it, which is the entire point of it being a row.
	 *
	 * <p>It names the ingredient and it names the next step, in the same voice the error catalogue
	 * uses, because a storekeeper reading the movement list is in exactly the position somebody
	 * reading an error message is: something is wrong, it is not their fault, and they need to know
	 * what to go and do. The quantity is not repeated here — it is the row's own {@code quantity}
	 * column, and a note that restates a column is a note that will one day disagree with it.
	 */
	private static String shortfallNote(AllocatedLine line, String callerNote) {
		String said = "Cooked with more " + line.ingredientName() + " than the store room's books held. "
				+ "The batches were drawn to zero and this is the remainder. "
				+ "Check that every delivery of it has been recorded.";
		return callerNote == null ? said : said + " — " + callerNote;
	}

	/**
	 * Puts back everything one dish drew, and says how many movements that took (T-007).
	 *
	 * <p>The mirror of {@link #consume}, and it lives here rather than in the caller for the same
	 * reason {@code consume} does: the meal planner owns the moment a dish stops having been cooked,
	 * and this is the service it calls. Nothing about the store room's internals — that a draw is one
	 * movement per batch, that a hand-corrected movement must be skipped rather than refused — needs
	 * to be known one layer up.
	 *
	 * <p><strong>Reverse before re-drawing, never after.</strong> A dish corrected from 400 servings
	 * to 640 has to give back the 400 first: draw the 640 against a shelf that still believes the
	 * first 400 are gone and a temple with just enough rice is refused for a shortfall that does not
	 * exist. The caller's transaction makes the pair atomic, so the intermediate state where the
	 * shelf is briefly full again is never observable outside it.
	 */
	@Transactional
	public int reverse(AuthenticatedUser actor, UUID mealPlanId, String note) {
		return stockMovementService.compensateAllFor(
				actor, MovementReference.MEAL_PLAN, mealPlanId, trimToNull(note));
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
