package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the meal planner asks while it plans (E4-S4), all behind {@code MANAGE_MEAL_PLANS}: the day's
 * context, the fast, last year's menu, sufficiency, what is going outside, event names, the drive for
 * an address not saved yet, and reusing a stretch of plan.
 *
 * <p><strong>Meals themselves are not here any more (D-27).</strong> This controller used to create,
 * read, edit, cancel and repeat one dish row at a time, because a dish row was all there was. A meal
 * is a row of its own now, saved with its dishes and its volunteer shift in one press, so reading and
 * writing meals is {@link MealController} at {@code /api/v1/meals}, addressed by the meal's id. What
 * stays is everything the planner asks about a <em>date</em> rather than about a meal.
 */
@RestController
@RequestMapping("/api/v1/meal-plans")
public class MealPlanController {

	private final MealPlanService mealPlanService;
	private final SufficiencyService sufficiencyService;
	private final MenuHistoryService menuHistoryService;

	public MealPlanController(
			MealPlanService mealPlanService, SufficiencyService sufficiencyService,
			MenuHistoryService menuHistoryService) {
		this.mealPlanService = mealPlanService;
		this.sufficiencyService = sufficiencyService;
		this.menuHistoryService = menuHistoryService;
	}

	/** What the planner should pre-fill for a date (day-type, festival, servings, Ekadashi). */
	@GetMapping("/day-context")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public DayContext dayContext(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

		return mealPlanService.dayContext(date);
	}

	/** Whether planning a recipe on a date raises an Ekadashi warning, and the offending ingredients. */
	@GetMapping("/ekadashi-check")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public EkadashiCheck ekadashiCheck(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam UUID recipeId) {

		return mealPlanService.ekadashiCheck(date, recipeId);
	}

	/**
	 * What was cooked for this festival last time, and what of it can still be planned (item 26b).
	 *
	 * <p>{@code before} is the date being planned, so the meal being composed is never offered back
	 * to itself. An occasion never cooked for comes back with a null {@code lastCookedOn}.
	 */
	@GetMapping("/menu-history")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public MenuHistoryView menuHistory(
			@RequestParam String occasionName,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before) {

		return menuHistoryService.lastMenuFor(occasionName, before);
	}

	/** Per-dish ingredient sufficiency across a range, with commitment accounting (E4-S5). */
	@GetMapping("/sufficiency")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<MealSufficiency> sufficiency(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		return sufficiencyService.sufficiency(from, to);
	}

	/** Aggregated shortfall across the ordering horizon — the contract E5-S2 consumes (E4-S5). */
	@GetMapping("/shortfall")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<ShortfallItem> shortfall() {
		return sufficiencyService.shortfallFeed();
	}

	// No /outside-commitments here any more (T-363, 2026-09-19). It fed one section at the foot of the
	// planner listing what the temple had undertaken to send out of the building, and Rajeev removed
	// that section: the events are ordinary meals and sit in the planner's day list with everything
	// else, sorted by ready-by. The one thing the section could do that a day cannot — look across
	// dates — is a heads-up rather than a screen, so it moved to Today, which reads the same rows
	// through TodayService and still answers with OutsideCommitment.

	/**
	 * The event names this temple has used before, newest first, with what each was last time
	 * (E4-S15 D9). {@code q} is a prefix, matched without regard to case; at most ten come back.
	 */
	@GetMapping("/event-names")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<EventSuggestion> eventNames(@RequestParam(name = "q", required = false) String q) {
		return mealPlanService.eventNames(q);
	}

	/**
	 * The travel estimate for an address that has not been saved yet — the same arithmetic as a
	 * saved meal's ({@code GET /api/v1/meals/{id}/travel-estimate}), for a form that has no meal id.
	 * Always 200; every way it can fail to produce a number is an unavailable estimate with a reason.
	 */
	@GetMapping("/travel-estimate")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public TravelEstimate travelEstimateFor(
			@RequestParam(required = false) String placeId,
			@RequestParam(required = false) Double latitude,
			@RequestParam(required = false) Double longitude,
			@RequestParam LocalDate planDate,
			@RequestParam LocalTime guestsEatAt) {
		return mealPlanService.travelEstimateFor(placeId, latitude, longitude, planDate, guestsEatAt);
	}

	/**
	 * What reusing a stretch of plan would do, without doing it (2026-09-05). A POST because it
	 * carries a body, not because it changes anything.
	 */
	@PostMapping("/reuse/preview")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ReusePlanPreview previewReuse(@Valid @RequestBody ReusePlanRequest request) {
		return mealPlanService.previewReuse(request);
	}

	/**
	 * Reuses a stretch of plan, meal by meal. Only ever adds: a target day with anything already
	 * planned is left alone whole, so pressing it twice is harmless.
	 */
	@PostMapping("/reuse")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ReusePlanResult reuse(
			@Valid @RequestBody ReusePlanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return mealPlanService.reusePlan(actor, request);
	}
}
