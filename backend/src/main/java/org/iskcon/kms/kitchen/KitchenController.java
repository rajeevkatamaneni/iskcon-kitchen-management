package org.iskcon.kms.kitchen;

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
 * The kitchens register (E10-S2).
 *
 * <p>Writing is {@code MANAGE_KITCHENS}, a Temple Admin's alone: which kitchens a temple runs is a
 * structural fact about the temple, like its settings, and deciding that another one exists is not
 * part of running one.
 *
 * <p><strong>Reading the list is not.</strong> It also rides on {@code REQUEST_INGREDIENTS},
 * because a cook cannot raise a request without choosing the kitchen it is for, and a picker that
 * only an administrator can fill is a picker nobody can use. Everything else here — the detail, and
 * every write — stays with {@code MANAGE_KITCHENS}.
 */
@RestController
@RequestMapping("/api/v1/kitchens")
public class KitchenController {

	private final KitchenService kitchenService;

	public KitchenController(KitchenService kitchenService) {
		this.kitchenService = kitchenService;
	}

	/**
	 * The temple's kitchens. Active only unless {@code includeArchived} is asked for, which is what
	 * the register itself shows and what somebody restoring one needs; a picker takes the default.
	 */
	@GetMapping
	@PreAuthorize("hasAnyAuthority('MANAGE_KITCHENS', 'REQUEST_INGREDIENTS')")
	public List<KitchenView> list(
			@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
		return kitchenService.list(includeArchived);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_KITCHENS')")
	public KitchenView get(@PathVariable UUID id) {
		return kitchenService.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_KITCHENS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateKitchenRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = kitchenService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_KITCHENS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateKitchenRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		kitchenService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Archive. What a kitchen that has asked the store for something gets instead of deletion, and
	 * reversible by {@link #restore} — a temple that closes the wrong kitchen should not need
	 * support to undo it.
	 */
	@PostMapping("/{id}/archive")
	@PreAuthorize("hasAuthority('MANAGE_KITCHENS')")
	public ResponseEntity<Void> archive(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		kitchenService.archive(actor, id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/restore")
	@PreAuthorize("hasAuthority('MANAGE_KITCHENS')")
	public ResponseEntity<Void> restore(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		kitchenService.restore(actor, id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Delete outright — refused with KMS-4973 for a kitchen any request has ever named, which is
	 * told to archive instead. The verb does what it says, and archiving has a URL that says what
	 * it is.
	 */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_KITCHENS')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		kitchenService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}
}
