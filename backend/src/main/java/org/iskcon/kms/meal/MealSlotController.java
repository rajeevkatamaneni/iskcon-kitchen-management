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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's meal slots (E4-S4). Planners read the list; curating it is a temple-settings decision.
 */
@RestController
@RequestMapping("/api/v1/meal-slots")
public class MealSlotController {

	private final MealSlotService mealSlotService;

	public MealSlotController(MealSlotService mealSlotService) {
		this.mealSlotService = mealSlotService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<MealSlotView> list() {
		return mealSlotService.list();
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateMealSlotRequest request) {
		UUID id = mealSlotService.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		mealSlotService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
