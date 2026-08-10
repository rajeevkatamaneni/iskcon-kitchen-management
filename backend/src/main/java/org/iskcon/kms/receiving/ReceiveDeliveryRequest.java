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
		@NotBlank @Size(max = 100) String idempotencyKey,
		@Size(max = 500) String deliveryNoteRef,
		@Size(max = 1000) String note,
		@NotEmpty @Valid List<ReceiptLineInput> lines) {
}
