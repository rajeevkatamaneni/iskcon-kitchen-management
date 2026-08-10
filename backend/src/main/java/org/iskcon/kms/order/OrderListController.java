package org.iskcon.kms.order;

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
 * The suggested order list (E5-S2), behind {@code MANAGE_PURCHASE_ORDERS}. Regenerated nightly and on
 * demand here; edits mark a line so regeneration leaves it be.
 */
@RestController
@RequestMapping("/api/v1/order-list")
public class OrderListController {

	private final OrderListService orderListService;

	public OrderListController(OrderListService orderListService) {
		this.orderListService = orderListService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public List<OrderListLineView> list() {
		return orderListService.list();
	}

	@PostMapping("/regenerate")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Map<String, Object>> regenerate() {
		int lines = orderListService.regenerateForCurrentTenant();
		return ResponseEntity.ok(Map.of("lines", lines));
	}

	@PatchMapping("/{ingredientId}")
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID ingredientId, @Valid @RequestBody UpdateOrderLineRequest request) {
		orderListService.updateLine(ingredientId, request);
		return ResponseEntity.noContent().build();
	}
}
