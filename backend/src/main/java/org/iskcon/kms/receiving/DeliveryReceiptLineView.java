package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One line of a recorded delivery, as the Received tab lists it (R-DEL-2): what was kept, what was
 * rejected and why, and how much of it has since gone back to the vendor. No price (R-DEL-5).
 *
 * <p>{@code returnedQty} is summed from {@code goods_returns} at read time, exactly as
 * {@link GoodsReceiptLineView#returnedQty()} is, because the receipt line itself is append-only.
 *
 * <p>{@code returns} is each of those returns on its own, oldest first, with its reason and date (T-285).
 * The mock's Returned column reads "Paneer 1 Kg, spoiled, 17 Sept", and a sum cannot say why or when.
 * {@code returnedQty} stays as it was: it is what the total of {@code returns} comes to, and the
 * screens that only need the amount keep reading it.
 */
public record DeliveryReceiptLineView(
		UUID poLineId,
		String itemName,
		String unit,
		BigDecimal receivedQty,
		BigDecimal rejectedQty,
		RejectReason rejectReason,
		BigDecimal returnedQty,
		List<DeliveryReturnView> returns) {
}
