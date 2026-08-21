package org.iskcon.kms.meal;

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
 * How many people each meal takes, and how many it has (item 24).
 *
 * <p>Behind {@code MANAGE_MEAL_PLANS}, the same permission that governs the plans these figures are
 * read from and the same one {@code /api/v1/workforce} already sits behind. What is exposed is a
 * head count and a target — no name, no job title, no salary. A cook can already see who is standing
 * next to them.
 */
@RestController
@RequestMapping("/api/v1/meal-crew")
public class MealCrewController {

	/** A month at a time is the widest any caller needs, matching the workforce endpoint it reads. */
	private static final int MAX_DAYS = 62;

	private final MealCrewService mealCrew;

	public MealCrewController(MealCrewService mealCrew) {
		this.mealCrew = mealCrew;
	}

	/** Every meal in the range with its readout: <em>Rostered · 3 staff · 2 volunteers · 5 of 8</em>. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<MealCrewView> range(
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
		return mealCrew.crewFor(from, to);
	}

	/**
	 * What to open the crew counter at for a new meal of this kind: the median of the last three
	 * ordinary meals of it, or nothing at all where the temple has never recorded one.
	 */
	@GetMapping("/suggested")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public SuggestedCrew suggested(@RequestParam String mealKind) {
		return new SuggestedCrew(mealCrew.suggestedCrew(mealKind));
	}

	/**
	 * The default, which may be nothing.
	 *
	 * <p>Its own shape rather than a bare number so that "we have never cooked one of these" can come
	 * back as null and be drawn as an empty field. A zero would read as a statement about the world.
	 */
	public record SuggestedCrew(Integer crewRequired) {
	}
}
