package org.iskcon.kms.inventory;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumable inventory: the stock view and the items behind it (E3-S1), all under
 * {@code MANAGE_INVENTORY}. Note what is missing by design — there is no way to <em>set</em> a stock
 * level. Stock is the sum of the ledger; it moves only when a movement is recorded.
 */
@RestController
@RequestMapping("/api/v1/inventory/items")
public class InventoryItemController {

	private final InventoryItemService inventoryItemService;

	public InventoryItemController(InventoryItemService inventoryItemService) {
		this.inventoryItemService = inventoryItemService;
	}

	/** The stock list. Filter by storage location and/or category; tune the expiring-soon window. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public List<StockItemView> list(
			@RequestParam(required = false) String location,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) Integer expiringWithinDays) {

		return inventoryItemService.list(location, category, expiringWithinDays);
	}

	/** The consumables below their reorder level — the dashboard's low-stock list and count. */
	@GetMapping("/low-stock")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public List<StockItemView> lowStock() {
		return inventoryItemService.lowStock();
	}

	/** One consumable's stock, broken out by batch (FEFO). */
	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public StockDetailView get(
			@PathVariable UUID id,
			@RequestParam(required = false) Integer expiringWithinDays) {

		return inventoryItemService.get(id, expiringWithinDays);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateInventoryItemRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = inventoryItemService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateInventoryItemRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		inventoryItemService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {

		inventoryItemService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Manually correct a batch's stock (E3-S7). Behind {@code MANAGE_INVENTORY} — routine kitchen
	 * work — but a large correction is refused here unless the actor may approve one, so the size
	 * check lives in the service where the current stock is known, not in the annotation.
	 */
	@PostMapping("/{id}/adjustments")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> adjust(
			@PathVariable UUID id,
			@Valid @RequestBody AdjustStockRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID movementId = inventoryItemService.adjust(actor, id, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", movementId));
	}
}
