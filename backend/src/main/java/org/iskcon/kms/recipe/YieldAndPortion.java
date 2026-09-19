package org.iskcon.kms.recipe;

/**
 * The two units a recipe request carries, so {@link PortionFitsYieldValidator} can read either of
 * the two request records that hold them — creating a recipe and editing one — without a copy of
 * itself for each.
 */
public interface YieldAndPortion {

	/** What the recipe is measured in: KG, GM, L, ML or PIECES. */
	String baseYieldUnit();

	/** What one person's portion is measured in, or null where the recipe states no portion. */
	String perHeadUnit();
}
