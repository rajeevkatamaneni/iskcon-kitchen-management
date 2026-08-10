package org.iskcon.kms.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side of the Vaishnava calendar (E4-S1), behind {@code MANAGE_MEAL_PLANS} — the planner and
 * its consumers. Always reads precomputed rows; no astronomy runs on a request.
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

	/** Guard against an unbounded scan; the planner never needs more than the precompute horizon. */
	private static final int MAX_RANGE_DAYS = 550;

	private final CalendarService calendarService;

	public CalendarController(CalendarService calendarService) {
		this.calendarService = calendarService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<CalendarDayView> range(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "range", "reason", "from..to must be in order and within the horizon"));
		}
		return calendarService.range(from, to);
	}

	@GetMapping("/{date}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<CalendarDayView> day(
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

		return calendarService.day(date)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}
}
