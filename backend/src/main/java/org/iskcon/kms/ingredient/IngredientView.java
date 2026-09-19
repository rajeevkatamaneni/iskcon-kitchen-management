package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The full view of an ingredient, for the catalogue list and detail. */
public record IngredientView(
		UUID id,
		String name,
		String category,
		String unit,
		boolean ekadashiProhibited,
		/**
		 * A consumable supply rather than food — LPG, leaf plates, dishwashing liquid, hand soap,
		 * first aid (D-1). It is sent on every ingredient, food or not, because the recipe picker
		 * on the client decides what to offer from this field alone; an absent key would deserialise
		 * to the permissive answer there exactly as it does here.
		 */
		boolean supply,
		/**
		 * True where a recipe import created this row rather than a person typing it (T-119).
		 *
		 * <p>Importing a library recipe creates every ingredient the temple does not already have,
		 * silently and deliberately — {@code RecipeImportService} calls a review step in front of
		 * every import "the kind of friction that stops a feature being used at all". The column has
		 * existed since V69 and until now nothing read it, so the catalogue filled up with rows
		 * nobody chose and there was no way to tell them apart.
		 *
		 * <p>It says how the row got here and nothing more. An import-created ingredient may be
		 * perfectly good; the import picks a category from the name and takes the unit from the
		 * book's own quantity, and both are often right. So the screens label it
		 * "Added by a Recipe Import" rather than "unchecked" or "needs details".
		 *
		 * <p>It is cleared by {@link IngredientService#update} — saving an edit <em>is</em> the
		 * review — which is what keeps the filter a queue that empties rather than one that only
		 * grows.
		 */
		boolean libraryDerived,
		List<String> aliases,
		Instant createdAt,
		/**
		 * The ingredient's alternate units, smallest first (R-ING-1): "250 gm", "Bag = 25 Kg". Empty
		 * when it has none, never null. Sent on the list as well as the detail, loaded for the whole
		 * catalogue in one query ({@link PackSizeService#allByIngredient}).
		 */
		List<PackSizeView> packSizes,
		/**
		 * What it would cost to buy today, in rupees per canonical unit (R-ING-3), or null when no
		 * market rate has been set. Read here only; it is written by the market-rate service (T-254)
		 * at stock-take, from an invoice line, or by hand. The three fields are all set or all null,
		 * which V144's {@code ingredients_market_rate_shape} holds.
		 */
		BigDecimal marketRate,
		/** The day the market rate was set, or null. */
		LocalDate marketRateOn,
		/** Where it came from: STOCK_TAKE, INVOICE or MANUAL, or null. */
		String marketRateSource) {
}
