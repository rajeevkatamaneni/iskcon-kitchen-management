package org.iskcon.kms.receiving;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A single delivery being recorded against a PO (E5-S6). The {@code idempotencyKey} makes the whole
 * submission one unit: a retry or double-click carrying the same key returns the receipt already
 * recorded rather than booking stock twice (SYSTEM_DESIGN §6).
 */
public record ReceiveDeliveryRequest(
		@NotBlank(message = "Reload the page and record this delivery again.")
		@Size(max = 100, message = "Reload the page and record this delivery again.")
		String idempotencyKey,
		@Size(max = 500, message = "That reference is too long.") String deliveryNoteRef,
		@Size(max = 1000, message = "That note is too long.") String note,
		@NotEmpty(message = "A delivery needs at least one line.") @Valid List<ReceiptLineInput> lines) {
}
