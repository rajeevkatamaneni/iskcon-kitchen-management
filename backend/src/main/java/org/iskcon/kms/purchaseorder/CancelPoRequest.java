package org.iskcon.kms.purchaseorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancel a purchase order (E5-S3). A reason is required. */
public record CancelPoRequest(@NotBlank @Size(max = 500) String reason) {
}
