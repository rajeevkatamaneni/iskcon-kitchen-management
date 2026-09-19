package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * Meals, each addressed by its own id (D-27): planned with their dishes and volunteer shift in one
 * press, read with everything about them, recorded from the returned card, corrected, repeated and
 * cancelled.
 *
 * <p><strong>Why one controller where there were two.</strong> A meal used to be read two ways —
 * dish rows at {@code /api/v1/meal-plans} and grouped meals at {@code /api/v1/meal-services} — because
 * there were two tables that each held half of it, joined by a date and two names. There is one meal
 * row now, and one address for it.
 *
 * <p><strong>Permissions are unchanged.</strong> Everything here is {@code MANAGE_MEAL_PLANS}, the
 * permission that has always governed planning and recording, apart from correcting a recorded meal,
 * which is {@code CORRECT_RECORDED_MEAL} and the Temple Admin's alone (T-007, D-4). Saving a meal's
 * volunteer shift needs nothing more: every role that plans meals — Temple Admin, Kitchen Manager and
 * Kitchen Staff — also manages volunteer shifts (D-27, checked against {@code RolePermissions}).
 */
@RestController
@RequestMapping("/api/v1/meals")
public class MealController {

	private final MealPlanService mealPlanService;
	private final ServedMealService servedMealService;

	public MealController(MealPlanService mealPlanService, ServedMealService servedMealService) {
		this.mealPlanService = mealPlanService;
		this.servedMealService = servedMealService;
	}

	/**
	 * The meals in a range, each with its dishes, its card, its recording and its live volunteer
	 * shift — what the planner draws a day or a week from.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<ServedMeal> list(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		return mealPlanService.meals(from, to);
	}

	/**
	 * How many meals in the range went out and were never written down, and how many plates each
	 * meal on {@code from} came to. One source, so the screens showing them cannot disagree by one.
	 */
	@GetMapping("/summary")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> summary(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		return Map.of(
				"unrecorded", servedMealService.unrecordedCount(from, to),
				"platesByMealKind", servedMealService.platesByMealKind(from));
	}

	/** One meal, with its live volunteer shift — the counts the cancel warning quotes are on it. */
	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ServedMeal get(@PathVariable UUID id) {
		return mealPlanService.meal(id);
	}

	/**
	 * Plans a meal: "Save this meal". The meal, its dishes and its volunteer shift are committed
	 * together or not at all. A meal already planned for that day, kind and event name — cancelled or
	 * not — is reused, and the answer carries its id.
	 *
	 * <p>201 with {@code {id, warning?}}. The warning is not a refusal: it is the one travel failure a
	 * planner can fix, a delivery address the map service could not place (KMS-400078).
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody SaveMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		SavedMeal saved = mealPlanService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(body(saved));
	}

	/**
	 * Changes a meal that has not been recorded: "Update this meal". Always 200 with
	 * {@code {id, warning?}}, so a caller never has to branch on whether there was a body.
	 */
	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return body(mealPlanService.update(actor, id, request));
	}

	/**
	 * Cancels a meal and its volunteer shift (D-27, answer 5) — or, with {@code scope}
	 * {@code THIS_AND_LATER}, this meal and every later occurrence of its repeating event (T-307). The
	 * body is optional, and without one this is exactly the cancel it always was.
	 *
	 * <p>Answers {@code {volunteersTold, mealsCancelled, lastDate}}. {@code volunteersTold} is what it
	 * always answered; the other two are new and say how many meals were cancelled and, for a meal in
	 * a series, the date of the last occurrence still standing.
	 */
	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public CancelledMeals cancel(
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) CancelMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return mealPlanService.cancel(actor, id, request);
	}

	/**
	 * What "Cancel this and every later one" would cancel, for the confirmation (T-307). Refused with
	 * KMS-400178 for a meal that is not a repeating event.
	 */
	@GetMapping("/{id}/later-in-series")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public LaterInSeries laterInSeries(@PathVariable UUID id) {
		return mealPlanService.laterInSeries(id);
	}

	/**
	 * Repeats an event "once every N weeks until a date" (Rajeev, 2026-09-19), as a series: the source
	 * and every copy share it, and each copy is still an ordinary meal, editable and cancellable on its
	 * own. No volunteer shift is copied. Supersedes E4-S15 D8's "copies, not a series".
	 */
	@PostMapping("/{id}/repeat")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public RepeatEventResult repeat(
			@PathVariable UUID id,
			@Valid @RequestBody RepeatEventRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return mealPlanService.repeat(actor, id, request.everyWeeks(), request.until());
	}

	/**
	 * What {@code POST /{id}/repeat} would do with the same two answers, writing nothing: the dates it
	 * would make, the ones it would skip and why, and the series afterwards. Refused exactly as the
	 * repeat is, because it is the same walk.
	 */
	@GetMapping("/{id}/repeat-preview")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public RepeatEventResult repeatPreview(
			@PathVariable UUID id,
			@RequestParam int everyWeeks,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until) {

		return mealPlanService.previewRepeat(id, everyWeeks, until);
	}

	/** When to leave the temple for this meal's delivery (E4-S16). Always 200. */
	@GetMapping("/{id}/travel-estimate")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public TravelEstimate travelEstimate(@PathVariable UUID id) {
		return mealPlanService.travelEstimate(id);
	}

	/** What actually went out, for the whole meal, from the returned job card (B5). */
	@PostMapping("/{id}/record")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ServedMeal record(
			@PathVariable UUID id,
			@Valid @RequestBody RecordMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return servedMealService.record(actor, id, request);
	}

	/**
	 * Corrects what a recorded meal actually served, moving the stock with it (T-007).
	 *
	 * <p><strong>The one act here that is not {@code MANAGE_MEAL_PLANS}.</strong> Recording is everyday
	 * kitchen work. Correcting rewrites a number that stock consumption and every materials figure have
	 * already inherited, so D-4 gives it to the Temple Admin alone: widening a permission later is one
	 * line in a diff, narrowing one after temples have built a habit around it is a conversation with
	 * every one of them.
	 */
	@PostMapping("/{id}/correct")
	@PreAuthorize("hasAuthority('CORRECT_RECORDED_MEAL')")
	public ServedMeal correct(
			@PathVariable UUID id,
			@Valid @RequestBody CorrectMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return servedMealService.correct(actor, id, request);
	}

	private static Map<String, Object> body(SavedMeal saved) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", saved.id());
		if (saved.warning() != null) {
			body.put("warning", ErrorResponse.of(saved.warning()));
		}
		return body;
	}
}
