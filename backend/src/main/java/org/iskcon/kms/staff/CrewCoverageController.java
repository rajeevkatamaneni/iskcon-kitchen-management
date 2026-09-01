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
 * Where the schedule is short of hands (E6-S15).
 *
 * <p>One endpoint and not three. The staff schedule asks two questions of it — <em>this week</em>
 * for the foot of the grid and <em>the next thirty days</em> for the list beside it — and both are
 * the same question over a different range. Splitting them would have left two shapes to keep
 * agreeing with each other about the same Thursday.
 *
 * <p>Behind {@code MANAGE_STAFF_SCHEDULE}, the permission that already gates the grid this feeds.
 * That is not a widening: both roles holding it — Temple Admin and Kitchen Manager — also hold
 * {@code MANAGE_MEAL_PLANS}, which is what gates the crew figures on their own. What is exposed is a
 * head count and a target. No name, no job title, no pay.
 */
@RestController
@RequestMapping("/api/v1/crew-coverage")
public class CrewCoverageController {

	/**
	 * A month at a time, matching {@code /api/v1/workforce} and {@code /api/v1/meal-crew} — the two
	 * endpoints this reads. A wider range would be answered by resolving a roster they refuse to.
	 */
	private static final int MAX_DAYS = 62;

	private final CrewCoverageService coverage;

	public CrewCoverageController(CrewCoverageService coverage) {
		this.coverage = coverage;
	}

	/** Every date in the range with its shortfall, including the ones with nothing planned. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public List<DayCoverageView> range(
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
		return coverage.coverage(from, to);
	}
}
