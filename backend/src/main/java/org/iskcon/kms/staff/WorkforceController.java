package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How many hands there are, per date (B1/B3).
 *
 * <p>Behind {@code MANAGE_MEAL_PLANS} rather than a workforce permission, and that is a deliberate
 * widening. The pebbles this feeds sit on the meal planner and the tile sits on Today, both of which
 * kitchen staff see every morning; gating the number behind the roster permission would leave those
 * two screens showing an empty box to the very people cooking that day. What is exposed is a head
 * count — how many staff and how many volunteers. No name, no job title, no salary, no PAN. A cook
 * can already see who is standing next to them.
 */
@RestController
@RequestMapping("/api/v1/workforce")
public class WorkforceController {

	/** A month at a time is the widest any caller needs; a year-long range is a mistake, not a request. */
	private static final int MAX_DAYS = 62;

	private final WorkforceService workforce;

	public WorkforceController(WorkforceService workforce) {
		this.workforce = workforce;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<WorkforceCount> range(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		if (to.isBefore(from)) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "to", "reason", "the last date falls before the first"));
		}
		if (to.toEpochDay() - from.toEpochDay() >= MAX_DAYS) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "to", "reason", "ask for at most " + MAX_DAYS + " days at a time"));
		}
		return workforce.listFor(from, to);
	}
}
