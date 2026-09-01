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

	/** The report's default span when a caller names neither end: the four weeks up to today. */
	private static final int DEFAULT_PERIOD_DAYS = 27;

	private final MaterialsCostService service;
	private final MealKindCostService byMealKind;

	public MaterialsCostController(MaterialsCostService service, MealKindCostService byMealKind) {
		this.service = service;
		this.byMealKind = byMealKind;
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

	/**
	 * The same estimate, kept split by kind of meal over a period, with a figure per serving (E3-S9).
	 *
	 * <p>Behind the same permission as the daily figure, and deliberately so: it is the same fact
	 * about the same cooking, asked a different way. Anyone who may read what today's food costs may
	 * read what a plate of it costs.
	 *
	 * <p>Both dates are optional and default to the four weeks up to today at the temple, for the same
	 * reason the daily figure defaults to the temple's own day.
	 */
	@GetMapping("/by-meal-kind")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public CostByMealKind byMealKind(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate end = to == null ? LocalDate.now(TEMPLE_ZONE) : to;
		LocalDate start = from == null ? end.minusDays(DEFAULT_PERIOD_DAYS) : from;
		return byMealKind.byMealKind(start, end);
	}
}
