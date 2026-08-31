package org.iskcon.kms.ingredientrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.iskcon.kms.inventory.BatchOverride;

/**
 * Recording that the goods went over the counter (E10-S7). One act, one transaction, and the moment
 * the temple's stock actually falls.
 *
 * <p>Everything here is optional. An empty {@code lines} issues every line at the quantity that was
 * approved, which is what the storekeeper who checked the sheet and handed it all over wants to
 * press once.
 */
public record RecordIssueRequest(

		/** Only the lines that differ from what was approved need appear. */
		@Valid List<IssuedLineInput> lines,

		/**
		 * A batch pinned to the front of the queue, where the storekeeper is taking from an opened
		 * lot rather than the one that expires first. The rest still follow FEFO.
		 */
		@Valid List<BatchOverride> batchOverrides,

		@Size(max = 2000, message = "That note is too long.")
		String note) {
}
