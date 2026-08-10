package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.iskcon.kms.inventory.BatchOverride;

/**
 * Mark a planned meal cooked (E4-S4), which draws its ingredients from stock (E3-S6). Optional
 * per-ingredient batch choices override the default FEFO order; the note is stored on each
 * consumption movement.
 */
public record MarkCookedRequest(
		@Valid List<BatchOverride> batchOverrides,
		@Size(max = 500) String note) {
}
