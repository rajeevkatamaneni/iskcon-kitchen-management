package org.iskcon.kms.receiving;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * One vendor's van, across any of their open orders (R-DEL-3). The wire shape is
 * {@code RecordDeliveryInput} in {@code frontend/lib/api.ts}.
 *
 * <p>{@code idempotencyKey} makes the whole press one unit, exactly as it does for
 * {@link ReceiveDeliveryRequest}: a retry or a double-click carrying the same key records nothing a
 * second time. The server derives one key per order from it (see {@code DeliveriesService}), because
 * the press becomes one goods receipt per order and each receipt keeps its own key.
 */
public record RecordDeliveryRequest(
		@NotNull(message = "Say which vendor this delivery is from.") UUID vendorId,
		@NotBlank(message = "Reload the page and record this delivery again.")
		@Size(max = 100, message = "Reload the page and record this delivery again.")
		String idempotencyKey,
		@NotEmpty(message = "A delivery needs at least one line.") @Valid List<RecordDeliveryLineInput> lines) {
}
