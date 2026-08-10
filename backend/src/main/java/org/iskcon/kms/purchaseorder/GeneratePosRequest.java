package org.iskcon.kms.purchaseorder;

import java.util.List;
import java.util.UUID;

/**
 * Generate draft POs from the order list (E5-S3): one PO per distinct vendor from the selected,
 * included lines. A null/empty {@code ingredientIds} means every included line that has a vendor.
 */
public record GeneratePosRequest(List<UUID> ingredientIds) {
}
