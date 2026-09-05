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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The temple's list of who fixes things (E3-S10 D7).
 *
 * <p>Every verb here is {@code MANAGE_EQUIPMENT_SERVICING}, the Temple Admin's alone — reading
 * included. This is not the picker problem {@code KitchenController} has, where a cook must read the
 * list to raise a request: the only screens that name a provider are the service schedule and the
 * record-a-service form, and both are already behind this permission. A read grant to somebody who
 * can do nothing with the answer would widen who can see the temple's contacts for nothing.
 */
@RestController
@RequestMapping("/api/v1/service-providers")
public class ServiceProviderController {

	private final ServiceProviderService serviceProviders;

	public ServiceProviderController(ServiceProviderService serviceProviders) {
		this.serviceProviders = serviceProviders;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public List<ServiceProviderView> list() {
		return serviceProviders.list();
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public ServiceProviderView get(@PathVariable UUID id) {
		return serviceProviders.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody ServiceProviderRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = serviceProviders.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody ServiceProviderRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		serviceProviders.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/** Refused with KMS-4017 for a provider any machine or any recorded service still names. */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {

		serviceProviders.delete(actor, id);
		return ResponseEntity.noContent().build();
	}
}
