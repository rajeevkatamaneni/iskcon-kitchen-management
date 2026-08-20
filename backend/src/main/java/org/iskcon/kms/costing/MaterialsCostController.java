package org.iskcon.kms.costing;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The estimated cost of a day's food (B2).
 *
 * <p>Behind {@code MANAGE_MEAL_PLANS} rather than a money permission. The figure is derived from
 * vendors' last-known prices, and everyone holding that permission can already read those same
 * prices on a purchase order and on the vendor's own page — so this leaks nothing new, and gating it
 * behind {@code MANAGE_VENDOR_PAYMENTS} would hide the kitchen's own cost from the kitchen.
 */
@RestController
@RequestMapping("/api/v1/materials-cost")
public class MaterialsCostController {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final MaterialsCostService service;

	public MaterialsCostController(MaterialsCostService service) {
		this.service = service;
	}

	/**
	 * The date is optional and defaults to today at the temple. The browser's idea of "today" is its
	 * own timezone's, and a devotee reading the tile from another country should still see the
	 * temple's day rather than one either side of it.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public MaterialsCost cost(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return service.costFor(date == null ? LocalDate.now(TEMPLE_ZONE) : date);
	}
}
