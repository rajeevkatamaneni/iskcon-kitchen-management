package org.iskcon.kms.vendor;

import jakarta.validation.constraints.Size;

/**
 * Deactivate or reactivate a vendor, with a reason.
 *
 * <p>Not annotated {@code @NotBlank}: the reason is required only on the way out, and a bean
 * constraint cannot see which endpoint it is on. {@code VendorService} enforces it, so a blank
 * deactivation reason comes back as {@code KMS-4011} — which says what is missing and why it
 * matters — rather than the generic validation failure.
 */
public record ChangeVendorStatusRequest(@Size(max = 500) String reason) {
}
