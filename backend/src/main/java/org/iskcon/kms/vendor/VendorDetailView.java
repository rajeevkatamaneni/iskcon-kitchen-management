package org.iskcon.kms.vendor;

import java.util.List;

/** A vendor with the ingredients it supplies (E5-S1). */
public record VendorDetailView(VendorView vendor, List<VendorSupplyView> supplies) {
}
