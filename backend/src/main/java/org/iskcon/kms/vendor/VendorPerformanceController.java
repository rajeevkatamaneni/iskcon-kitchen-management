package org.iskcon.kms.vendor;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor performance report (E5-S9).
 *
 * <p>Behind {@code MANAGE_VENDORS} rather than {@code MANAGE_PURCHASE_ORDERS}. Both are held by
 * exactly the same three roles in {@code RolePermissions} — Temple Admin, Kitchen Manager, Kitchen
 * Staff — so the choice widens and narrows nothing; it is about what the report is <em>about</em>.
 * Every figure here is a judgement on a supplier, and it is read for the same reason the vendor's
 * own page and its deactivation history are read: deciding whether to keep buying from them. Those
 * answer to {@code MANAGE_VENDORS}, and this belongs with them.
 *
 * <p>Its own path rather than {@code /vendors/performance}: a literal segment under a mapping that
 * already has {@code /{id}} works, but only because of how patterns are ranked, and a report about
 * every vendor is not a sub-resource of one.
 *
 * <p>The tenant comes from the verified token through RLS, as everywhere. There is no tenant
 * parameter to get wrong.
 */
@RestController
@RequestMapping("/api/v1/vendor-performance")
public class VendorPerformanceController {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	/** The span when a caller names neither end: the four weeks up to today, as the costing report. */
	private static final int DEFAULT_PERIOD_DAYS = 27;

	private final VendorPerformanceService service;

	public VendorPerformanceController(VendorPerformanceService service) {
		this.service = service;
	}

	/**
	 * Both dates are optional. They default to the temple's own four weeks up to today, because the
	 * browser's idea of today is its own timezone's and a devotee reading this from another country
	 * should see the temple's day rather than one either side of it.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public VendorPerformance report(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate end = to == null ? LocalDate.now(TEMPLE_ZONE) : to;
		LocalDate start = from == null ? end.minusDays(DEFAULT_PERIOD_DAYS) : from;
		return service.report(start, end);
	}
}
