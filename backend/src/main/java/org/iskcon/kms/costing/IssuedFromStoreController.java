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
 * What the store issued to each kitchen over a period, costed (E10-S13).
 *
 * <p><strong>Behind {@code MANAGE_INVENTORY}</strong>, and not behind the {@code MANAGE_MEAL_PLANS}
 * the other cost reports use. This one is a reading of the stock ledger rather than of the meal
 * planner: every figure in it is the sum of {@code ISSUE} rows that a holder of
 * {@code MANAGE_INVENTORY} can already open one at a time on the movement history, priced from
 * vendor prices the same person can already read on a purchase order. So it discloses nothing new —
 * it adds up what its readers can already see.
 *
 * <p>{@code MANAGE_KITCHENS} was the other candidate and is the wrong one. It gates deciding that a
 * kitchen exists, which is a structural act held apart from daily kitchen work on purpose (E10-S2),
 * and only a Temple Admin holds it. Using it here would lock the storekeeper — a Kitchen Manager —
 * out of the report about their own issuing, which is the one person who most needs to read it.
 */
@RestController
@RequestMapping("/api/v1/issued-from-store")
public class IssuedFromStoreController {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	/** The report's default span when a caller names neither end: the four weeks up to today. */
	private static final int DEFAULT_PERIOD_DAYS = 27;

	private final IssuedFromStoreService service;

	public IssuedFromStoreController(IssuedFromStoreService service) {
		this.service = service;
	}

	/**
	 * Both dates are optional and default to the four weeks up to today at the temple. The browser's
	 * idea of "today" is its own timezone's, and the store's day is the temple's.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public IssuedFromStore issuedFromStore(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate end = to == null ? LocalDate.now(TEMPLE_ZONE) : to;
		LocalDate start = from == null ? end.minusDays(DEFAULT_PERIOD_DAYS) : from;
		return service.issuedFromStore(start, end);
	}
}
