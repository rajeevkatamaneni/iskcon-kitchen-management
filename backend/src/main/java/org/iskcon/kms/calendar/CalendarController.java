package org.iskcon.kms.calendar;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Vaishnava calendar (E4-S1): reading precomputed days (behind {@code MANAGE_MEAL_PLANS} — the
 * planner and its consumers; no astronomy runs on a request), and admin overrides of individual
 * dates (E4-S3, behind {@code OVERRIDE_CALENDAR_DATE}).
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

	/** Guard against an unbounded scan; the planner never needs more than the precompute horizon. */
	private static final int MAX_RANGE_DAYS = 550;

	private final CalendarService calendarService;
	private final CalendarOverrideService overrideService;

	public CalendarController(CalendarService calendarService, CalendarOverrideService overrideService) {
		this.calendarService = calendarService;
		this.overrideService = overrideService;
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

	/** Override a single date (E4-S3). Temple Admin only; survives the nightly recompute. */
	@PutMapping("/{date}/override")
	@PreAuthorize("hasAuthority('OVERRIDE_CALENDAR_DATE')")
	public ResponseEntity<Void> setOverride(
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@Valid @RequestBody SetCalendarOverrideRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		overrideService.set(actor, date, request);
		return ResponseEntity.noContent().build();
	}

	/** Remove a date's override, reverting to the computed value (E4-S3). */
	@DeleteMapping("/{date}/override")
	@PreAuthorize("hasAuthority('OVERRIDE_CALENDAR_DATE')")
	public ResponseEntity<Void> revertOverride(
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		overrideService.revert(actor, date);
		return ResponseEntity.noContent().build();
	}
}

