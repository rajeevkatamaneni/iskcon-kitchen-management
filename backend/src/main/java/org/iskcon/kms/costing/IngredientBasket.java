package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;

/**
 * How much of each ingredient a piece of cooking needs, in its family's base unit.
 *
 * <p>This is the shape every costing question in the system starts from, and it is deliberately
 * ignorant of where the quantities came from. A basket may be scaled out of planned recipes (E3-S8,
 * E3-S9) or read off the stock ledger; the pricing that follows is the same either way, which is the
 * whole reason it is a type rather than a local map.
 *
 * <p>Two things it keeps that a plain {@code Map<UUID, BigDecimal>} could not.
 *
 * <p><strong>Quantities merge.</strong> A recipe may list the same ingredient twice — ghee in the
 * tempering and ghee in the finish — and it is bought once. So does a week of lunches: the rice of
 * Monday and the rice of Tuesday are one line on the estimate.
 *
 * <p><strong>The units it was asked in are remembered.</strong> Everything is accumulated in the
 * family base (grams, millilitres, pieces), which is what makes 200 gm and 0.2 Kg the same
 * quantity — but the families themselves cannot be reconciled. An ingredient asked for in litres
 * whose catalogue unit is Kg cannot be priced without inventing a density, and a basket that had
 * thrown the family away would have no way to know it. So it is kept, and the costing declines to
 * guess.
 */
public final class IngredientBasket {

	private final Map<UUID, BigDecimal> baseQuantities = new LinkedHashMap<>();
	private final Map<UUID, EnumSet<Unit.Family>> familiesUsed = new LinkedHashMap<>();

	/** Adds a quantity as it was written — the unit it was written in is converted and remembered. */
	public void add(UUID ingredientId, BigDecimal quantity, Unit unit) {
		baseQuantities.merge(ingredientId, InventoryUnits.toBase(quantity, unit), BigDecimal::add);
		familiesUsed.computeIfAbsent(ingredientId, k -> EnumSet.noneOf(Unit.Family.class)).add(unit.family());
	}

	/** Folds another basket into this one. Used to build a day, a meal kind or a period from meals. */
	public void addAll(IngredientBasket other) {
		other.baseQuantities.forEach((id, base) -> baseQuantities.merge(id, base, BigDecimal::add));
		other.familiesUsed.forEach((id, families) ->
				familiesUsed.computeIfAbsent(id, k -> EnumSet.noneOf(Unit.Family.class)).addAll(families));
	}

	public boolean isEmpty() {
		return baseQuantities.isEmpty();
	}

	/** The ingredients in the basket, in the order they were first asked for. */
	public Set<UUID> ingredientIds() {
		return Collections.unmodifiableSet(baseQuantities.keySet());
	}

	/** How much of one ingredient the basket holds, in its family's base unit. */
	public BigDecimal baseQuantity(UUID ingredientId) {
		return baseQuantities.get(ingredientId);
	}

	/**
	 * The unit families this ingredient was asked for in. More than one means the recipes disagree
	 * about what kind of thing it is, and nothing downstream may convert it.
	 */
	public Set<Unit.Family> familiesUsed(UUID ingredientId) {
		return familiesUsed.getOrDefault(ingredientId, EnumSet.noneOf(Unit.Family.class));
	}
}
