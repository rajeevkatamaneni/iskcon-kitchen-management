package org.iskcon.kms.equipment;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Equipment inventory (E3-S4), behind {@code MANAGE_INVENTORY}. Condition is never a field edit — it
 * moves only through {@code POST /{id}/condition}, which insists on a reason and records the change.
 */
@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {

	private final EquipmentService equipmentService;

	public EquipmentController(EquipmentService equipmentService) {
		this.equipmentService = equipmentService;
	}

	/** The equipment list. Scrapped items are hidden unless {@code includeScrapped=true}. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public List<EquipmentView> list(
			@RequestParam(required = false, defaultValue = "false") boolean includeScrapped,
			@RequestParam(required = false) EquipmentCategory category,
			@RequestParam(required = false) String location) {

		return equipmentService.list(includeScrapped, category, location);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public EquipmentDetailView get(@PathVariable UUID id) {
		return equipmentService.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateEquipmentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = equipmentService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateEquipmentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		equipmentService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/** Change condition — sent for repair, returned, scrapped — with a mandatory reason. */
	@PostMapping("/{id}/condition")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Void> changeCondition(
			@PathVariable UUID id,
			@Valid @RequestBody ChangeConditionRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		equipmentService.changeCondition(actor, id, request);
		return ResponseEntity.noContent().build();
	}
}
