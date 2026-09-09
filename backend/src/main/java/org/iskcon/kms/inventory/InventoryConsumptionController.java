package org.iskcon.kms.inventory;

import jakarta.validation.Valid;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consuming stock to cook a meal (E3-S6), behind {@code MANAGE_INVENTORY}. The meal planner (E4) owns
 * the screen; these are the two calls it makes — preview to show the cook the drawdown and any
 * shortfalls, then consume to commit it.
 */
@RestController
@RequestMapping("/api/v1/inventory/consumption")
public class InventoryConsumptionController {

	private final InventoryConsumptionService consumptionService;

	public InventoryConsumptionController(InventoryConsumptionService consumptionService) {
		this.consumptionService = consumptionService;
	}

	/** Plan the drawdown and report shortfalls, writing nothing. */
	@PostMapping("/preview")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ConsumptionPlan preview(@Valid @RequestBody ConsumeRequest request) {
		return consumptionService.preview(request.recipeId(), request.targetYield(), request.batchOverrides());
	}

	/**
	 * Commit the drawdown: this dish was cooked.
	 *
	 * <p><strong>Never refused on stock grounds (T-087).</strong> This is the recording half of the
	 * pair, and by the time it is called the food is made. What the batches could not cover is
	 * booked as a {@code USED_BEYOND_RECORDED_STOCK} movement instead, and the response carries
	 * {@code sufficient} and the shortfalls so the caller can see what had to be booked that way.
	 * The planning half above still says no — {@code sufficient} comes back false and every short
	 * ingredient is itemised — it simply says it without refusing a fact.
	 */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<ConsumptionPlan> consume(
			@Valid @RequestBody ConsumeRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		return ResponseEntity.status(HttpStatus.CREATED).body(consumptionService.consume(actor, request));
	}
}
