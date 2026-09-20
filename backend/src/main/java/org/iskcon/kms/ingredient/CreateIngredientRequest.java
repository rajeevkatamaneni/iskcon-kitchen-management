package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to add an ingredient to the catalogue. The unit is a string validated in the service
 * against {@link Unit}. Setting {@code ekadashiProhibited} true is permitted only to a Temple Admin
 * (MANAGE_DIETARY_POLICY), checked in the service; for everyone else it must be false.
 *
 * <p>Setting {@code notBought} true is permitted only to a Temple Admin too, under a different
 * permission — {@code MANAGE_BUYING_POLICY} (T-402) — and checked in the same place for the same
 * reason.
 *
 * <p>{@code supply} deliberately carries no such split. Deciding an ingredient is prohibited is a
 * religious-compliance call; saying a thing is a mop is not, so it is ordinary catalogue editing
 * under {@code MANAGE_RECIPES} and no second permission was invented for it (D-1).
 */
public record CreateIngredientRequest(

		@NotBlank(message = "Enter the ingredient's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@NotBlank(message = "Choose a category.")
		@Size(max = 100, message = "That category name is too long.")
		String category,

		@NotBlank(message = "Choose a unit.")
		String unit,

		/** Optional Ekadashi-prohibited (grain/bean) flag; false unless a Temple Admin sets it. */
		boolean ekadashiProhibited,

		/**
		 * Whether this is a consumable supply — LPG, leaf plates, dishwashing liquid — rather than
		 * food (D-1). A primitive, so an absent key deserialises to {@code false}, which is the
		 * permissive answer: unflagged means food and food reaches the recipe picker. That is why
		 * the client type declares it required rather than optional, so every caller says which it
		 * is out loud instead of relying on this default.
		 */
		boolean supply,

		/**
		 * Whether the temple never buys this — water, ice (T-402). A marked ingredient is left off
		 * every shopping list from creation, so nobody has to untick it once per list.
		 *
		 * <p>{@code true} needs {@code MANAGE_BUYING_POLICY} and is refused with
		 * {@code KMS-400021 NOT_PERMITTED} without it, exactly as {@code ekadashiProhibited} is
		 * refused without {@code MANAGE_DIETARY_POLICY}. A primitive, so an absent key deserialises
		 * to {@code false} — which is the permissive answer here in the sense that matters: the
		 * temple goes on buying the thing, which is safe. The client type declares it required all
		 * the same, so a recipe import saying "this is water" cannot lose that by omission.
		 */
		boolean notBought,

		/** Optional alternate names, matched by typeahead alongside the name. */
		List<@Size(max = 200, message = "That alias is too long.") String> aliases,

		/**
		 * The person was shown "Did you mean Curd?" and confirmed this is a different ingredient
		 * (R-DUP-2, T-251). {@code true} lets a save through the lookalike check and puts the override
		 * on the audit trail with the name it looked like; absent or {@code false} means the check
		 * applies. Boxed so an old client that never sends the key is simply checked. It never
		 * overrides the literal same-name refusal, which the database would refuse anyway — see
		 * {@code IngredientService.guardAgainstLookalike}.
		 */
		Boolean confirmDifferent) {
}
