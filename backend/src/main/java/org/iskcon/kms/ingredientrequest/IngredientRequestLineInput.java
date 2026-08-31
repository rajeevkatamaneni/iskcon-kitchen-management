package org.iskcon.kms.ingredientrequest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;

/**
 * One thing the kitchen is asking the store for.
 *
 * <p>The unit is not free choice. It must belong to the same family as the ingredient's own
 * canonical unit, which the service checks against the catalogue: 500 gm of a rice held in kilograms
 * is the same substance measured differently and is accepted, 3 litres of it is not a quantity of
 * rice at all. Doing that check in the service rather than here is deliberate — the rule is about
 * two fields at once, and one of them lives in another table.
 */
public record IngredientRequestLineInput(

		@NotNull(message = "Choose an ingredient.")
		UUID ingredientId,

		@NotNull(message = "Enter how much is needed.")
		@DecimalMin(value = "0.001", message = "Enter an amount greater than zero.")
		BigDecimal quantity,

		@NotNull(message = "Choose a unit.")
		Unit unit,

		/** "The older sack, it has been open a while." Optional, and read by the storekeeper. */
		@Size(max = 500, message = "That note is too long.")
		String note) {
}
