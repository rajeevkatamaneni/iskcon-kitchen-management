package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meal planning (E4-S4), all behind {@code MANAGE_MEAL_PLANS}. Calendar and list views read the same
 * plans. What is going out of the temple has a list of its own — {@code /outside-commitments} — keyed
 * off whether the food leaves rather than off what kind of meal it is (E4-S15 D4), so the school
 * delivery is on it beside the wedding.
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

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<MealPlanView> list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) MealStatus status,
			@RequestParam(required = false) DayType dayType) {

		return mealPlanService.list(from, to, status, dayType);
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
	 * <p>{@code before} is the date being planned. The meal being composed carries the same occasion
	 * name from its first saved preparation onwards, so without it the composer would be offered back
	 * the preparations it has just put in.
	 *
	 * <p>An occasion the temple has never cooked for comes back with a null {@code lastCookedOn} and
	 * an empty list, and the control that offers the menu is simply absent. A refusal would be the
	 * wrong answer: there is nothing wrong with the first ever Janmashtami.
	 */
	@GetMapping("/menu-history")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public MenuHistoryView menuHistory(
			@RequestParam String occasionName,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before) {

		return menuHistoryService.lastMenuFor(occasionName, before);
	}

	/** Per-meal ingredient sufficiency across a range, with commitment accounting (E4-S5). */
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

	/**
	 * What is going out of the temple from today onwards, soonest first (E4-S15 D4).
	 *
	 * <p>Keyed off whether the food leaves rather than off whether somebody is paying for it, so a
	 * delivery to a school and a community programme are on it beside the wedding — those are exactly
	 * as easy to forget on the morning.
	 */
	@GetMapping("/outside-commitments")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<OutsideCommitment> outsideCommitments() {
		return mealPlanService.outsideCommitments();
	}

	/**
	 * The event names this temple has used before, newest first, with what each was last time
	 * (E4-S15 D9).
	 *
	 * <p>{@code q} is a prefix, matched without regard to case; absent or blank, the answer is
	 * simply the most recently used names. At most ten come back, and each carries the whole shape of
	 * the most recent plan of that name — outside or not, the handover, the contact and the address —
	 * so choosing one fills the form rather than only the field.
	 *
	 * <p>This is the endpoint the whole split leans on. The temple has no event register today, so
	 * events are a practice being introduced and not one being digitised; if planning a Saturday
	 * reading costs three minutes it will stop being planned, and the record will be worse than it
	 * was before events existed.
	 */
	@GetMapping("/event-names")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<EventSuggestion> eventNames(@RequestParam(name = "q", required = false) String q) {
		return mealPlanService.eventNames(q);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public MealPlanView get(@PathVariable UUID id) {
		return mealPlanService.get(id);
	}

	/**
	 * When to leave the temple for this delivery (E4-S16).
	 *
	 * <p>Always 200. Every way this can fail to produce a number — no map service, an address nobody
	 * could place, a service having a bad minute, a meal that is not a delivery at all — comes back
	 * as an unavailable estimate with a reason, which the screen renders as one quiet line. A missing
	 * travel estimate is not an error in the meal plan, and it is never a reason to refuse one.
	 */
	/**
	 * The travel estimate for an address that has not been saved yet.
	 *
	 * <p>The sibling below takes a plan id, which is no use in a form: a planner is typing an address
	 * and choosing a serving time, and there is nothing to take an id of until they press save. This
	 * is the same arithmetic — from the temple to a place, backwards from the time the guests eat —
	 * for a delivery that does not exist.
	 *
	 * <p>It answers with the same {@link TravelEstimate} and the same reason codes, so one line on
	 * the screen renders both. The figure the planner ends up saving is theirs: prefilled from this,
	 * and adjustable by anybody who knows the road.
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

	@GetMapping("/{id}/travel-estimate")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public TravelEstimate travelEstimate(@PathVariable UUID id) {
		return mealPlanService.travelEstimate(id);
	}

	/**
	 * What reusing a stretch of plan would do, without doing it (2026-09-05).
	 *
	 * <p>A POST because it carries a body, not because it changes anything — it is read-only, and the
	 * screen calls it again on every tick. It answers with what is in the source window as well as
	 * what would land, so one call serves both halves of the screen.
	 */
	@PostMapping("/reuse/preview")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ReusePlanPreview previewReuse(@Valid @RequestBody ReusePlanRequest request) {
		return mealPlanService.previewReuse(request);
	}

	/**
	 * Reuses a stretch of plan — or one day of it, which is how a festival is carried to next year.
	 *
	 * <p>Only ever adds: a target day with anything already planned is left alone whole, so pressing
	 * it twice is harmless and the answer says what it declined to do. Replaces the week-shaped
	 * {@code duplicate-week} below, which is now this with {@code days = 7}.
	 */
	@PostMapping("/reuse")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ReusePlanResult reuse(
			@Valid @RequestBody ReusePlanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return mealPlanService.reusePlan(actor, request);
	}

	/**
	 * Plans a preparation.
	 *
	 * <p>The answer carries a {@code warning} where there is one, and there is exactly one thing that
	 * warns: a delivery address the map service could not place (KMS-4993, E4-S16). <strong>It is not
	 * a refusal.</strong> The plan is saved and complete, and a map service's opinion of a street name
	 * is not a reason to throw away everything somebody typed — but it is worth saying, because it is
	 * the one travel failure they can fix.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateMealPlanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		SavedMealPlan saved = mealPlanService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(body(saved));
	}

	/**
	 * Edits a preparation that has not been cooked yet.
	 *
	 * <p>204 as it always was, and 200 with a {@code warning} in the body in the one case that has
	 * something to say — the address that could not be placed. A caller that only looked at the
	 * status code sees a success either way.
	 */
	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Map<String, Object>> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMealPlanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		SavedMealPlan saved = mealPlanService.update(actor, id, request);
		return saved.warning() == null
				? ResponseEntity.noContent().build()
				: ResponseEntity.ok(body(saved));
	}

	private static Map<String, Object> body(SavedMealPlan saved) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", saved.id());
		if (saved.warning() != null) {
			body.put("warning", ErrorResponse.of(saved.warning()));
		}
		return body;
	}

	/**
	 * Repeats an event forward for a number of weeks (E4-S15 D8). What it makes is copies: each one
	 * is editable and cancellable on its own, and nothing ever has to ask *this one or all of them?*
	 */
	@PostMapping("/{id}/repeat")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public RepeatEventResult repeat(
			@PathVariable UUID id,
			@RequestParam int weeks,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return mealPlanService.repeatForward(actor, id, weeks);
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Void> cancel(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {

		mealPlanService.cancel(actor, id);
		return ResponseEntity.noContent().build();
	}

	// There is no per-dish "mark cooked" here any more (brief §2). A meal is recorded once, as a
	// whole, from the job card that came back — see MealServiceController.
}
