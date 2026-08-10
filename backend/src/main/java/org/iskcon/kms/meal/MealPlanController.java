package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.inventory.ConsumptionPlan;
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

	public MealPlanController(MealPlanService mealPlanService, SufficiencyService sufficiencyService) {
		this.mealPlanService = mealPlanService;
		this.sufficiencyService = sufficiencyService;
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

	/** Cook the meal — draw its ingredients from stock — and return what was drawn. */
	@PostMapping("/{id}/cooked")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ConsumptionPlan markCooked(
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) MarkCookedRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return mealPlanService.markCooked(actor, id, request);
	}
}
