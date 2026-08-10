package org.iskcon.kms.inventory;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * A cook's choice to draw a particular ingredient from a particular batch, overriding the default
 * first-expiry-first order (E3-S6) — for instance to use up an opened lot first. The chosen batch is
 * consumed before the rest, which still follow FEFO.
 */
public record BatchOverride(
		@NotNull UUID ingredientId,
		@NotNull UUID batchId) {
}
