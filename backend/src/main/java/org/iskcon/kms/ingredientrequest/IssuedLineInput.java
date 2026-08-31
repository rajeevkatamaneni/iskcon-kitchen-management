package org.iskcon.kms.ingredientrequest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;

/**
 * What the storekeeper actually handed over on one line.
 *
 * <p>The form arrives pre-filled with what was approved, so the common case is the storekeeper
 * confirming it. A line left out of the issue is taken as approved-in-full for the same reason.
 *
 * <p><strong>Zero is a legitimate answer</strong> and is not the same as leaving the line out: it
 * says the store handed over nothing for this ingredient, which is a fact worth keeping. It writes
 * no stock movement at all — the same rule a dish recorded as not made already follows.
 */
public record IssuedLineInput(

		@NotNull(message = "Say which line this is.")
		UUID lineId,

		@NotNull(message = "Enter how much went out.")
		@DecimalMin(value = "0", message = "An issued amount cannot be negative.")
		BigDecimal quantity,

		/** In the same family as the ingredient, as the request's own line was. */
		@NotNull(message = "Choose a unit.")
		Unit unit) {
}
