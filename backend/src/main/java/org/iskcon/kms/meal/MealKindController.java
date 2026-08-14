package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The temple's kinds of meal, and the times they are due (E4-S7).
 *
 * <p>Anyone who plans meals reads the list — the planner needs to know a Deity offering has no
 * default time. Changing it is a temple decision: what time the temple eats is not something a cook
 * should be able to alter mid-shift, so writes sit behind {@code MANAGE_TEMPLE_SETTINGS}, which only
 * a Temple Admin holds.
 */
@RestController
@RequestMapping("/api/v1/meal-kinds")
public class MealKindController {

	private final MealKindService mealKindService;

	public MealKindController(MealKindService mealKindService) {
		this.mealKindService = mealKindService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<MealKindView> list() {
		return mealKindService.list();
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateMealKindRequest request) {
		UUID id = mealKindService.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id, @Valid @RequestBody CreateMealKindRequest request) {
		mealKindService.update(id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		mealKindService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
