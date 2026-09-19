package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One pack size being added to an ingredient (R-ING-1): an optional name, a number and a unit —
 * "Bag", 25, KG for "Bag = 25 Kg", or no name, 500, GM for a plain "500 gm".
 *
 * <p>The unit is a string checked in {@link PackSizeService} against {@link Unit}, as it is on the
 * ingredient's own create and update bodies, so an unreadable unit is the same refusal everywhere.
 * Whether it is in the ingredient's family is also the service's question, because only the service
 * has the ingredient in hand.
 *
 * <p>{@code @Digits} mirrors the column, NUMERIC(14, 3). Without it a figure with eleven or more
 * whole digits would reach PostgreSQL as a numeric overflow and come back as an internal error, and
 * a fourth decimal would be rounded away silently — "0.0005 Kg" stored as nothing, then refused by
 * the positive CHECK with no words for why.
 */
public record AddPackSizeRequest(

		/** "Bag", "Tin", "Pack"… Optional: a blank or absent name is a plain size ("500 gm"). */
		@Size(max = 40, message = "That pack name is too long. Keep it to 40 characters.")
		String name,

		@NotNull(message = "Enter how much one pack holds.")
		@Positive(message = "Enter an amount greater than zero.")
		@Digits(integer = 11, fraction = 3, message = "Enter the amount with at most 3 decimal places.")
		BigDecimal quantity,

		@NotBlank(message = "Choose a unit.")
		String unit) {
}
