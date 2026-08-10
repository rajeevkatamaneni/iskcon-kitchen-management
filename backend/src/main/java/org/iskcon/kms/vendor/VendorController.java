package org.iskcon.kms.vendor;

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

/** Vendor management (E5-S1), all behind {@code MANAGE_VENDORS}. */
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorController {

	private final VendorService vendorService;

	public VendorController(VendorService vendorService) {
		this.vendorService = vendorService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public List<VendorView> list(
			@RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
		return vendorService.list(includeInactive);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public VendorDetailView get(@PathVariable UUID id) {
		return vendorService.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateVendorRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = vendorService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateVendorRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		vendorService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/deactivate")
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public ResponseEntity<Void> deactivate(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		vendorService.setActive(actor, id, false);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/reactivate")
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public ResponseEntity<Void> reactivate(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		vendorService.setActive(actor, id, true);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{id}/supplies")
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public ResponseEntity<Void> setSupply(
			@PathVariable UUID id, @Valid @RequestBody SetVendorSupplyRequest request) {
		vendorService.setSupply(id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}/supplies/{ingredientId}")
	@PreAuthorize("hasAuthority('MANAGE_VENDORS')")
	public ResponseEntity<Void> removeSupply(
			@PathVariable UUID id, @PathVariable UUID ingredientId) {
		vendorService.removeSupply(id, ingredientId);
		return ResponseEntity.noContent().build();
	}
}
