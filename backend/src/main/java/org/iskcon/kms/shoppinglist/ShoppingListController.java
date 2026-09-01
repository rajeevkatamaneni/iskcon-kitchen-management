package org.iskcon.kms.shoppinglist;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The suggested shopping list (E5-S2), behind {@code MANAGE_PURCHASE_ORDERS}. Regenerated nightly and on
 * demand here; edits mark a line so regeneration leaves it be.
 */
@RestController
@RequestMapping("/api/v1/shopping-list")
public class ShoppingListController {

	private final ShoppingListService shoppingListService;

	public ShoppingListController(ShoppingListService shoppingListService) {
		this.shoppingListService = shoppingListService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<ShoppingListLineView> list() {
		return shoppingListService.list();
	}

	@PostMapping("/regenerate")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> regenerate() {
		int lines = shoppingListService.regenerateForCurrentTenant();
		return ResponseEntity.ok(Map.of("lines", lines));
	}

	@PatchMapping("/{ingredientId}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID ingredientId, @Valid @RequestBody UpdateShoppingListLineRequest request) {
		shoppingListService.updateLine(ingredientId, request);
		return ResponseEntity.noContent().build();
	}
}
