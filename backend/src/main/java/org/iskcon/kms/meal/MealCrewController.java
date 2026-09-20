package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.staff.WorkforceCount;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How many people each meal takes, and how many it has (item 24).
 *
 * <p>Behind {@code MANAGE_MEAL_PLANS}, the same permission that governs the plans these figures are
 * read from and the same one {@code /api/v1/workforce} already sits behind. What is exposed is a
 * head count, a target and, since Epic 12, the rostered staff's names per kitchen — no job title, no
 * salary, no contact detail. A cook can already see who is standing next to them.
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
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		if (to.isBefore(from)) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "to", "reason", "the last date falls before the first"));
		}
		if (to.toEpochDay() - from.toEpochDay() >= MAX_DAYS) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "to", "reason", "ask for at most " + MAX_DAYS + " days at a time"));
		}
		// Each meal's kitchens come in the order this person sees them: their own kitchen first (Epic 12).
		return mealCrew.crewFor(from, to, actor.getUserId());
	}

	/**
	 * Who is rostered at this date and ready-by for a meal that has not been saved yet (T-215).
	 *
	 * <p>The composer asks it before the first save, so <em>Rostered</em> reads a real figure on a new
	 * event and <em>Volunteers requested</em> opens on People needed minus that figure (D-27 answer 1).
	 * A GET, and nothing behind it writes: no meal, no day and no shift is created by asking, because
	 * nothing in the planner is saved until the meal is (D-27 answer 7).
	 *
	 * <p>The ready-by is required. A saved meal is always counted at its ready-by, and a count "for the
	 * whole day" here would be a different figure from the one the meal reads once saved — the exact
	 * disagreement this endpoint exists to remove. Until a time is given the composer says it has not
	 * counted, which is true.
	 *
	 * <p>Same permission as the rest of the crew figures. Since Epic 12 the rostered staff come by name,
	 * because the planner prints them under each kitchen; a cook can already see who is standing next to
	 * them, and a planner allocating People needed per kitchen has to know who is in which.
	 *
	 * <p><strong>Per kitchen (Epic 12).</strong> With {@code kitchenId}, only that kitchen's staff count.
	 * Volunteers belong to no kitchen, so the caller says with {@code countVolunteers} whether this is
	 * the section the meal's volunteers fall to (the main kitchen's, else the first); false leaves them
	 * out. Without a kitchen every staff member counts and volunteers always do, as before.
	 */
	@GetMapping("/at")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public CrewAt at(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam LocalTime readyBy,
			@RequestParam(required = false) UUID kitchenId,
			@RequestParam(defaultValue = "true") boolean countVolunteers) {
		MealCrewService.CrewAtCount count = mealCrew.crewAt(date, readyBy, kitchenId, countVolunteers);
		return new CrewAt(date, readyBy, count.staffIn(), count.volunteers(), count.rostered(), count.staffNames());
	}

	/**
	 * The three figures a {@link MealCrewView} carries, for a moment with no meal behind it.
	 *
	 * <p>Its own shape rather than a {@code MealCrewView} with the meal's fields left null. That record
	 * promises a meal id a reader can open, and a crew target to be short of; neither exists before the
	 * first save, and a null id handed to code that opens meals by id is a bug waiting for a reader.
	 *
	 * @param rostered staff and volunteers added, the figure People needed is measured against —
	 *                 added by {@link WorkforceCount#rostered()}, the one place that sum is made.
	 * @param staffNames the rostered staff by name, in the roster's order; {@code staffIn} is its size
	 *                   (Epic 12)
	 */
	public record CrewAt(
			LocalDate planDate, LocalTime readyBy, int staffIn, int volunteers, int rostered,
			List<String> staffNames) {
	}

	/**
	 * What to open the crew counter at for a new meal of this kind: the median of the last three
	 * ordinary meals of it, or nothing at all where the temple has never recorded one.
	 */
	@GetMapping("/suggested")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public SuggestedCrew suggested(
			@RequestParam String mealKind, @AuthenticationPrincipal AuthenticatedUser actor) {
		// For the kitchen the composer opens its first band on: this person's (Epic 12).
		return new SuggestedCrew(mealCrew.suggestedCrew(mealKind, actor.getUserId()));
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
