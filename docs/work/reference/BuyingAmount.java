package org.iskcon.kms.shoppinglist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;

/**
 * How much to ask a vendor for, given how much the temple needs (T-243).
 *
 * <p>Rajeev, 2026-09-19, looking at the Kalasipalya Vegetable Mandi tile on the shopping list: curry
 * leaves suggested at <em>"2792 gm"</em>. His words: <em>"this is not gold — we are purchasing food
 * items."</em> Nobody asks a vegetable vendor for 2.79 kilos. The figure the list works out is what
 * the kitchen <em>needs</em>; what it should <em>buy</em> is that figure taken up to a step a vendor
 * actually sells in — 3 Kg.
 *
 * <p><strong>Always up, never down.</strong> Rounding 2792 gm to the nearest half kilo would give
 * 2.5 Kg and leave the kitchen 292 gm short on the day. The list exists so the temple is not short,
 * so the only direction this goes is up, and the most it can add is one step.
 *
 * <p><strong>This is a different rule from {@code Quantities.cooks}, on purpose.</strong> That one
 * rounds to the <em>nearest</em> step for a figure somebody reads or weighs out — a recipe line, a
 * job card. This one decides a quantity that is then stored, sent to a vendor and received against,
 * so it has to be one number, rounded once, in one direction. Its steps are all multiples of the
 * cook's-form steps at the same size, so a buying amount printed through {@code Quantities.cooks}
 * comes out unchanged: 3000 gm reads "3 Kg", 450 gm reads "450 gm".
 *
 * <p><strong>Where pack sizes will go.</strong> Rajeev's next step (2026-09-19) is for an ingredient
 * to carry the sizes its vendors actually sell — tea leaves in 250 gm, 500 gm and 1 Kg packs — so
 * the suggestion lands on a pack rather than on a step. That is why {@link #of} already takes a
 * list of pack sizes: the caller passes an empty list today, and when the sizes exist it passes
 * them, and nothing that calls this has to change. What the rule for choosing among packs is — the
 * fewest packs, the least waste, whether 1.2 Kg of tea is a 1 Kg and a 250 gm or two 1 Kg packs — is
 * a decision nobody has made yet, so it is not guessed at here: a non-empty list is refused loudly
 * rather than quietly ignored.
 */
public final class BuyingAmount {

	/**
	 * <strong>The buying steps — Rajeev's rule, 2026-09-19. Change them here and nowhere else.</strong>
	 *
	 * <p>Read as: a quantity up to {@code upTo} (in grams for a mass, millilitres for a volume — the
	 * two families use the same table) is rounded up to the next multiple of {@code step}. The first
	 * band whose {@code upTo} the quantity does not exceed is the one used; the last band has no top.
	 *
	 * <pre>
	 *   up to 1 Kg / 1 L        next 50 gm / 50 ml        430 gm   →  450 gm
	 *   up to 10 Kg / 10 L      next 0.5 Kg / 0.5 L       2792 gm  →  3 Kg
	 *   up to 100 Kg / 100 L    next 1 Kg / 1 L           12.2 Kg  →  13 Kg
	 *   above 100 Kg / 100 L    next 5 Kg / 5 L           101 Kg   →  105 Kg
	 * </pre>
	 *
	 * <p>The boundaries belong to the band below them: exactly 1 Kg is already a whole 50 gm and a
	 * whole half kilo, so it stays 1 Kg whichever band claims it, and the same holds at 10 Kg and at
	 * 100 Kg. Anything over a boundary, by however little, moves to the next band's step — 1001 gm
	 * is bought as 1.5 Kg, because the vendor sells half kilos at that size and not grams.
	 *
	 * <p>Counted things (pieces) are not in this table. They are bought whole, and nothing else.
	 */
	private static final List<Step> STEPS = List.of(
			new Step(new BigDecimal("1000"), new BigDecimal("50")),
			new Step(new BigDecimal("10000"), new BigDecimal("500")),
			new Step(new BigDecimal("100000"), new BigDecimal("1000")),
			new Step(null, new BigDecimal("5000")));

	private record Step(BigDecimal upTo, BigDecimal step) {
	}

	private BuyingAmount() {
	}

	/**
	 * The quantity to buy, in {@code unit}, for a need of {@code needed} in {@code unit}.
	 *
	 * <p>The answer is in the same unit that was passed in, because that is the unit the shopping
	 * list stores and the purchase order line is written in; showing it as "3 Kg" rather than
	 * "3000 gm" is the screen's and the printed sheet's job, and both already do it.
	 *
	 * @param needed how much is needed; zero or less is returned unchanged, because a line asking
	 *     for nothing is dropped by the caller and there is nothing to round
	 * @param unit the unit {@code needed} is in — the ingredient's own stored unit
	 * @param packSizes the sizes the ingredient is sold in, in {@code unit}. Empty until pack sizes
	 *     exist; see the class comment for why a non-empty list is refused for now.
	 */
	public static BigDecimal of(BigDecimal needed, Unit unit, List<BigDecimal> packSizes) {
		if (!packSizes.isEmpty()) {
			throw new UnsupportedOperationException(
					"Pack sizes are not yet used to choose a buying amount; pass an empty list.");
		}
		if (needed.signum() <= 0) {
			return needed;
		}
		if (unit.family() == Unit.Family.COUNT) {
			return needed.setScale(0, RoundingMode.CEILING);
		}

		// Worked in the family's base unit, so one table serves Kg and gm alike: a need of 2.792 Kg
		// and a need of 2792 gm are the same need and must come out as the same 3 Kg.
		BigDecimal base = InventoryUnits.toBase(needed, unit);
		BigDecimal step = stepFor(base);
		BigDecimal bought = base.divide(step, 0, RoundingMode.CEILING).multiply(step);
		return InventoryUnits.fromBase(bought, unit);
	}

	/** The step for a quantity in base units — the first band it does not exceed. */
	static BigDecimal stepFor(BigDecimal base) {
		for (Step s : STEPS) {
			if (s.upTo() == null || base.compareTo(s.upTo()) <= 0) {
				return s.step();
			}
		}
		throw new IllegalStateException("The last buying step has no upper bound, so this is unreachable.");
	}
}
