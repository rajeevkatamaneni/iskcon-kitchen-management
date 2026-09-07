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
 *
 * <p>Three of the verbs here are narrower, and deliberately (E3-S10 D10; D-15). Registering equipment,
 * reading it and moving its condition stay with {@code MANAGE_INVENTORY}: kitchen staff are the ones
 * standing in front of the grinder when it stops. Setting the service interval and recording that a
 * service happened need {@code MANAGE_EQUIPMENT_SERVICING}, held by the Temple Admin alone, because
 * both commit the temple to money and to a date. Bringing a scrapped machine back is narrower
 * again — {@code REINSTATE_SCRAPPED_EQUIPMENT}, the Temple Admin's alone — because undoing a
 * disposal is a different kind of act from recording one.
 *
 * <p>The derived service fields — next date, what it was counted from, and where that stands against
 * the temple's warning horizon — ride on every read and are therefore visible to everyone who may
 * read the register. That is on purpose: a cook who can see the grinder is due next week is a cook
 * who mentions it, and nothing on those three fields is an act.
 */
@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {

	private final EquipmentService equipmentService;

	public EquipmentController(EquipmentService equipmentService) {
		this.equipmentService = equipmentService;
	}

	/**
	 * The equipment list. Scrapped items are hidden unless {@code includeScrapped=true}.
	 *
	 * <p>{@code serviceStatus=OVERDUE} is what the Today nudge links to, so the count on the
	 * dashboard and the list it opens are the same derivation rather than two that can disagree.
	 * A scrapped machine is NOT_SCHEDULED whatever its dates say, so it is in no such list even when
	 * {@code includeScrapped} asks for it (D6).
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public List<EquipmentView> list(
			@RequestParam(required = false, defaultValue = "false") boolean includeScrapped,
			@RequestParam(required = false) String location,
			@RequestParam(required = false) ServiceStatus serviceStatus) {

		return equipmentService.list(includeScrapped, location, serviceStatus);
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

	/**
	 * Bring a scrapped machine back, in the condition it comes back in, with a reason (D-15).
	 *
	 * <p>A second endpoint rather than a flag on {@code /condition}, because the guard in
	 * {@code changeCondition} is what keeps a scrapped item inert and a request body that could
	 * switch it off would be a guard in name only. So {@code /condition} goes on refusing every
	 * post-scrap edit with KMS-400043, unchanged, and this is the one named way back.
	 *
	 * <p>Narrower than everything else on the register, and deliberately (D-15, confirmed by Rajeev
	 * 2026-09-07): {@code REINSTATE_SCRAPPED_EQUIPMENT} is the Temple Admin's alone, by the same
	 * reasoning D-4 gave for voiding a donation. Undoing a disposal is a different kind of act from
	 * recording one, temples build habits around whoever may do it, and widening later is one line
	 * while narrowing later is a conversation with every temple.
	 */
	@PostMapping("/{id}/reinstate")
	@PreAuthorize("hasAuthority('REINSTATE_SCRAPPED_EQUIPMENT')")
	public ResponseEntity<Void> reinstate(
			@PathVariable UUID id,
			@Valid @RequestBody ReinstateEquipmentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		equipmentService.reinstate(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Set or clear how often this must be serviced, and who services it (E3-S10 D3, D7).
	 *
	 * <p>Its own endpoint rather than fields on the edit form, because the permission differs: this
	 * is the administrator's decision and the edit form is everybody's. Sending neither a count nor
	 * a unit clears the schedule; sending a blank company clears that.
	 *
	 * <p>The company is two plain text fields, a name and a number, and has been since V90 —
	 * D7's managed list was reversed on 2026-09-04 for being more machinery than the fact deserved.
	 * There is no longer a {@code /api/v1/service-providers} to keep beside this.
	 */
	@PutMapping("/{id}/service-schedule")
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public ResponseEntity<Void> setServiceSchedule(
			@PathVariable UUID id,
			@Valid @RequestBody ServiceScheduleRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		equipmentService.setServiceSchedule(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Record a service that has happened (E3-S10 D2).
	 *
	 * <p>Append-only: there is no verb here that edits or removes one, and no field anywhere that
	 * sets "last serviced" directly. A date in the future is refused with KMS-400016.
	 */
	@PostMapping("/{id}/services")
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public ResponseEntity<Map<String, Object>> recordService(
			@PathVariable UUID id,
			@Valid @RequestBody RecordServiceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID serviceId = equipmentService.recordService(actor, id, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", serviceId));
	}
}
