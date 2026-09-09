package org.iskcon.kms.receiving;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sending part or all of one received line back to the vendor (T-013).
 *
 * <p><strong>One line per request, and not a list.</strong> Receiving is multi-line because a lorry
 * is: the storekeeper stands at the gate with the whole delivery in front of them and keys it in one
 * pass. A return is the opposite shape — somebody opens a sack, finds weevils, and sends that sack
 * back. Nothing else on the delivery is in question. A return of several lines at once is a case
 * that can be described but not named from anything the temple actually does, so it is two requests
 * rather than a list that is one element long every time.
 *
 * <p>{@code quantity} is positive: how much went back. It becomes a negative movement in the ledger,
 * where the sign belongs.
 *
 * <p>The {@code idempotencyKey} makes a retry or a double-click a no-op rather than a second
 * withdrawal, exactly as it does on a receipt — and it matters more here, because the ledger is
 * append-only and a stock draw that got in twice can only be undone by a compensating adjustment.
 */
public record ReturnGoodsRequest(
		@NotBlank @Size(max = 100) String idempotencyKey,
		@NotNull UUID receiptLineId,
		@NotNull @Positive BigDecimal quantity,
		@NotNull ReturnReason reason,
		@Size(max = 1000) String note) {
}
