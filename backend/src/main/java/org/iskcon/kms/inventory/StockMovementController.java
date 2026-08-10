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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stock ledger's read and correction surface (E3-S2), all behind {@code MANAGE_INVENTORY} —
 * kitchen work. Movements are never <em>created</em> here directly: a movement is always the
 * consequence of a named action (receiving goods, recording a donation, cooking a meal, adjusting a
 * count), each with its own endpoint in a later story. What this controller offers is reading the
 * history and correcting a mistake, the two things that operate on the ledger as such.
 */
@RestController
@RequestMapping("/api/v1/inventory/movements")
public class StockMovementController {

	private final StockMovementService movementService;

	public StockMovementController(StockMovementService movementService) {
		this.movementService = movementService;
	}

	/** Movement history, newest first. Filter by consumable, by type, or neither. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public List<StockMovement> history(
			@RequestParam(required = false) UUID ingredientId,
			@RequestParam(required = false) MovementType type,
			@RequestParam(required = false) Integer limit) {

		return movementService.history(ingredientId, type, limit);
	}

	/** Corrects a movement by appending its reverse, cross-referencing the original. */
	@PostMapping("/{id}/compensate")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> compensate(
			@PathVariable UUID id,
			@Valid @RequestBody CompensateRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID correctionId = movementService.compensate(actor, id, request.note());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", correctionId));
	}
}
