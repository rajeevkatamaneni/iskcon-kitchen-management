package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.iskcon.kms.ingredient.Unit;

/**
 * Unit arithmetic shared across inventory. Stock is summed in a family's base unit — grams,
 * millilitres, pieces — so movements recorded in any unit of that family add up; this is the one
 * place that conversion and its inverse live.
 */
final class InventoryUnits {

	private InventoryUnits() {
	}

	/** The base unit of a family: GM for mass, ML for volume, PIECES for count. */
	static Unit baseUnit(Unit.Family family) {
		return switch (family) {
			case MASS -> Unit.GM;
			case VOLUME -> Unit.ML;
			case COUNT -> Unit.PIECES;
		};
	}

	/** A quantity in the given unit, expressed in its family's base unit. */
	static BigDecimal toBase(BigDecimal quantity, Unit unit) {
		return quantity.multiply(BigDecimal.valueOf(unit.baseFactor()));
	}

	/**
	 * A base-unit quantity back in {@code unit}, normalised to read cleanly: {@code 14}, not
	 * {@code 14.000}, and {@code 2.25} kept exact. Base factors are powers of ten, so the division
	 * never actually rounds.
	 */
	static BigDecimal fromBase(BigDecimal base, Unit unit) {
		BigDecimal value = base.divide(BigDecimal.valueOf(unit.baseFactor()), 3, RoundingMode.HALF_UP);
		if (value.signum() == 0) {
			return BigDecimal.ZERO;
		}
		value = value.stripTrailingZeros();
		return value.scale() < 0 ? value.setScale(0) : value;
	}
}
