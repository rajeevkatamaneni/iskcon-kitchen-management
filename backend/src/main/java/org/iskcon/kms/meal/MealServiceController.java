package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Meals as whole things, and the record of what went out at each (B5), behind
 * {@code MANAGE_MEAL_PLANS} — the same permission that governs the plans they are assembled from,
 * because they are the same information read at a different grain.
 *
 * <p>There is no per-dish "mark cooked" endpoint any more. Recording is one call for one meal.
 */
@RestController
@RequestMapping("/api/v1/meal-services")
public class MealServiceController {

	private final ServedMealService servedMealService;

	public MealServiceController(ServedMealService servedMealService) {
		this.servedMealService = servedMealService;
	}

	/** The meals in a range, each with its dishes, its card number and whether it has been recorded. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<ServedMeal> list(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		return servedMealService.list(from, to);
	}

	/**
	 * How many meals in the range went out and were never written down, and how many plates each kind
	 * of meal on {@code from} came to. Both figures exist so that the screens showing them read one
	 * source rather than each computing their own and disagreeing by one.
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

	/** What actually went out, for the whole meal, from the returned job card. */
	@PostMapping("/record")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public ServedMeal record(
			@Valid @RequestBody RecordMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return servedMealService.record(actor, request);
	}
}
