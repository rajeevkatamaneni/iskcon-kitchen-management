package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
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
 * plans; catering commitments are just plans filtered to {@code dayType=CATERING}. Marking a plan
 * cooked returns the stock movements it drew, so the client can show the confirmation preview.
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

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public MealPlanView get(@PathVariable UUID id) {
		return mealPlanService.get(id);
	}

	/**
	 * Copies the previous week into the week beginning {@code weekStart} (E3). Only ever adds — a day
	 * with anything already planned is left alone — so pressing it twice is harmless, and the answer
	 * says what it declined to do.
	 */
	@PostMapping("/duplicate-week")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public DuplicateWeekResult duplicateWeek(
			@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso =
					org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate weekStart,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return mealPlanService.duplicateWeek(actor, weekStart);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateMealPlanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = mealPlanService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateMealPlanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		mealPlanService.update(actor, id, request);
		return ResponseEntity.noContent().build();
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
