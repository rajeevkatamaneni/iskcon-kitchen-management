package org.iskcon.kms.receiving;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sending received goods back to the vendor (T-013).
 *
 * <p>Addressed by the receipt rather than by the purchase order, because a return is about one
 * delivery: the sacks that came on Tuesday's lorry, not the order they were ordered on. The order
 * is reachable from the receipt and never the other way round without ambiguity — a PO may take
 * several deliveries.
 *
 * <p><strong>Behind {@code MANAGE_INVENTORY}, and not {@code MANAGE_PURCHASE_ORDERS}</strong> like
 * the receiving endpoints beside it. The act being performed is taking stock off the temple's books;
 * whoever looks after the store room is who does it, and it is the same permission that governs an
 * adjustment or a consumption. Buying from a vendor and keeping the store honest are two jobs and
 * this is the second one.
 */
@RestController
@RequestMapping("/api/v1/goods-receipts/{receiptId}/returns")
public class GoodsReturnController {

	private final GoodsReturnService service;

	public GoodsReturnController(GoodsReturnService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public List<GoodsReturnView> list(@PathVariable UUID receiptId) {
		return service.listForReceipt(receiptId);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<GoodsReturnView> returnGoods(
			@PathVariable UUID receiptId,
			@Valid @RequestBody ReturnGoodsRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(service.returnGoods(actor, receiptId, request));
	}
}
