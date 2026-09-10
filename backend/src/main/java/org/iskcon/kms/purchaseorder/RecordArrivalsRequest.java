package org.iskcon.kms.purchaseorder;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * "These arrived" — the acknowledgement that closes an order carrying lines the store room cannot
 * take in (T-066).
 *
 * <p>Per line rather than per order, because an order from a hardware shop may carry four plastic
 * stools that came on Tuesday and a mixer motor repair that happens on Friday, and one button
 * claiming both would be a statement nobody made. The screen sends every line somebody ticked.
 *
 * <p><strong>There is no arrival date on this request, and its absence is a decision.</strong> The
 * arrival is recorded as the temple's today. Letting somebody type "they actually came on Tuesday"
 * needs a refusal for a date in the future or behind the order — which needs an error code, and
 * codes are allocated rather than taken. The column {@code arrived_on} is a DATE precisely so that
 * backdating can be added later without re-interpreting anything already stored. Flagged in
 * docs/work/proof/T-066.md.
 */
public record RecordArrivalsRequest(
		@NotEmpty(message = "Tick at least one line that arrived.") List<UUID> poLineIds) {
}
