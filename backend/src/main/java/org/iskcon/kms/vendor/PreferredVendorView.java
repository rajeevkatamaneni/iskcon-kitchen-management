package org.iskcon.kms.vendor;

import java.util.UUID;

/**
 * An ingredient's preferred vendor (R-VEN-2), as {@code GET /api/v1/vendors/preferred} lists it:
 * enough for the vendor page to say "Preferred (replaces Anand Stores)" before a tick is saved.
 * Mirrors the client type {@code PreferredVendorView} in {@code lib/api.ts} field for field.
 */
public record PreferredVendorView(UUID ingredientId, UUID vendorId, String vendorName) {
}
