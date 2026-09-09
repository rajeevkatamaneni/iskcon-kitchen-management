package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 *
 * <p><strong>One exception to that permission, and it is the last endpoint here (T-007).</strong>
 * Correcting a recorded meal is {@code CORRECT_RECORDED_MEAL}, the Temple Admin's alone (D-4).
 * Reading and recording are the same information at a different grain; correcting is a different act
 * on the same information, because it moves a figure stock and every materials estimate have already
 * been computed from.
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

	/**
	 * Corrects what a recorded meal actually served, moving the stock with it (T-007).
	 *
	 * <p><strong>The one act on this controller that is not {@code MANAGE_MEAL_PLANS}, and that is
	 * the whole point of it.</strong> Recording is everyday kitchen work — a returned card typed in
	 * by whoever is in the office — and admin, manager and kitchen staff all hold the permission for
	 * it. Correcting rewrites a number that stock consumption and every materials figure have already
	 * inherited, so D-4 gives it to the Temple Admin alone, on the same reasoning that split
	 * {@code APPROVE_LARGE_STOCK_ADJUSTMENT} out before it: widening a permission later is one line
	 * in a diff, narrowing one after temples have built a habit around it is a conversation with
	 * every one of them.
	 *
	 * <p>Addressed by the meal's own row rather than by date-and-kind-and-event, because a meal that
	 * has been recorded always has one — so unlike {@code /record}, there is no case where this is
	 * callable and the identity is ambiguous.
	 */
	@PostMapping("/{id}/correct")
	@PreAuthorize("hasAuthority('CORRECT_RECORDED_MEAL')")
	public ServedMeal correct(
			@PathVariable UUID id,
			@Valid @RequestBody CorrectMealRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return servedMealService.correct(actor, id, request);
	}
}
