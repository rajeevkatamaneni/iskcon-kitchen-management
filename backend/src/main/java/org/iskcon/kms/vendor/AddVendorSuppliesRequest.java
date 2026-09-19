package org.iskcon.kms.vendor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The ticked rows of the "Other ingredients" table, saved together (R-VEN-1).
 *
 * <p>Each row is the same {@link SetVendorSupplyRequest} the single edit sends, validated the same
 * way, so there is no second set of rules for what a supply may say. The list price on a row is
 * optional: a vendor being onboarded hands over a list, and the Temple Admin types what is on it.
 *
 * <p>The ceiling is the size of a temple's whole ingredient list with room to spare (about 230 at
 * the reference temple). It exists so that a runaway client cannot hold one transaction open over
 * an unbounded body, not because 500 is a meaningful number.
 */
public record AddVendorSuppliesRequest(
		@NotEmpty(message = "Tick at least one ingredient to add.")
		@Size(max = 500, message = "That is more ingredients than can be added at once. Add them in smaller groups.")
		List<@NotNull(message = "Tick at least one ingredient to add.") @Valid SetVendorSupplyRequest> rows) {
}
