package org.iskcon.kms.ingredientrequest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.iskcon.kms.ingredient.Unit;

/**
 * One dish the kitchen says it is cooking, and how much of it.
 *
 * <p>Text and numbers, and nothing else — no link to a recipe. Most of a temple's kitchens cook
 * things nobody here has ever written a recipe for, and a field that wanted to be a recipe reference
 * would either block them or train them to pick the nearest wrong thing from a dropdown.
 *
 * <p>The point of the field is not paperwork. Writing down "200 servings of khichdi" beside a
 * request for 40 kg of rice is what makes the requester think about the amount, and it is the other
 * half of the comparison an auditor needs six months later.
 */
public record IngredientRequestDishInput(

		@NotBlank(message = "Enter the dish's name.")
		@Size(max = 200, message = "That name is too long.")
		String dishName,

		@NotNull(message = "Enter how much of it is being made.")
		@DecimalMin(value = "0.001", message = "Enter an amount greater than zero.")
		BigDecimal quantity,

		/**
		 * Any unit food is genuinely made in — a sweet in litres, a pickle in kilos, idlis in
		 * pieces — plus {@link Unit#SERVINGS}, which is admitted here and on no ingredient line,
		 * because a meal is counted in people fed and a sack of rice never can be.
		 */
		@NotNull(message = "Choose a unit.")
		Unit unit) {
}
