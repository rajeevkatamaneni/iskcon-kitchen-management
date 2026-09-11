package org.iskcon.kms.shoppinglist;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
 * The suggested shopping list (E5-S2), behind {@code MANAGE_PURCHASE_ORDERS}.
 *
 * <p><strong>There are three endpoints here and none of them builds the list.</strong> {@code GET}
 * computes it from the meal plan, the store room, the vendor catalogue and the live purchase orders,
 * every time it is called; the other two record a decision about a line — a quantity, an untick, or
 * something added by hand — which is all this feature stores.
 *
 * <p>{@code POST /regenerate} was removed by T-132 along with the nightly job that called the same
 * method. Rajeev asked why the list needed a button when it could populate itself on load; the answer
 * was that it already populated itself once a night, and that neither door should exist. Nothing
 * replaces it, and the screen has no button where it used to be.
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

	/**
	 * Adds a line by hand (T-027). {@code PATCH} only ever changed a row that was already there, so
	 * a cook who could see the list was missing something had no way to say so — the omission this
	 * closes.
	 *
	 * <p>Same permission as its three neighbours: this is the same act as editing a quantity, done to
	 * a line that does not exist yet, and anyone who may change what the temple buys may also add to
	 * it. 201 with the created line, so the screen can render it without a second round trip.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<ShoppingListLineView> add(@Valid @RequestBody AddShoppingListLineRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(shoppingListService.addLine(request));
	}

	@PatchMapping("/{ingredientId}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID ingredientId, @Valid @RequestBody UpdateShoppingListLineRequest request) {
		shoppingListService.updateLine(ingredientId, request);
		return ResponseEntity.noContent().build();
	}
}
