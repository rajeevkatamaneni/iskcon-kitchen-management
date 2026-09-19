package org.iskcon.kms.ingredient.merge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * One merge group as the Temple Admin approved it (R-DUP-3). Mirrors {@code MergeGroupInput} in
 * {@code frontend/lib/api.ts}.
 *
 * <p>Nothing here is annotated as required. A group with no kept ingredient, nothing to merge, an id
 * twice or an id the temple does not have is one refusal — KMS-400173, "this merge group can't be used
 * as it is" — raised by the service, because every one of those is the same mistake from the person's
 * side and a field-by-field answer would only describe it four ways.
 *
 * @param supplyPriceChoices where one vendor supplies two of the group at different list prices, whose
 *     price that vendor keeps (R-DUP-3 step 4: "a conflict on the same vendor asks which price to
 *     keep"). Absent or empty is fine when there is no such vendor.
 */
public record MergeGroupInput(
		UUID keepIngredientId,
		@Valid List<Member> merge,
		List<SupplyPriceChoice> supplyPriceChoices) {

	/**
	 * An ingredient to merge away.
	 *
	 * @param preparationNote the note its recipe lines get. <strong>Null (or left out) means the
	 *     proposed note</strong> — the preparation {@code IngredientNameMatcher.split} finds in its
	 *     name, which is what the proposal showed. <strong>A blank string means no note.</strong> The
	 *     two have to differ because the screen's natural request is "what you proposed", and a person
	 *     who clears the box has said something else.
	 */
	public record Member(
			UUID ingredientId,
			@Size(max = 200, message = "That preparation note is too long.")
			String preparationNote) {
	}

	/** For one vendor: the ingredient of the group whose list price that vendor keeps. */
	public record SupplyPriceChoice(UUID vendorId, UUID keepPriceFromIngredientId) {
	}

	/** The members, never null. */
	public List<Member> members() {
		return merge == null ? List.of() : merge;
	}

	/** The price choices, never null. */
	public List<SupplyPriceChoice> choices() {
		return supplyPriceChoices == null ? List.of() : supplyPriceChoices;
	}
}
