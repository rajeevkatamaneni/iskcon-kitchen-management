package org.iskcon.kms.wishlist;

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

/** Wish-list management (E7-S5), behind {@code MANAGE_WISHLIST}. */
@RestController
@RequestMapping("/api/v1/wishlist")
public class WishlistController {

	private final WishlistService service;

	public WishlistController(WishlistService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_WISHLIST')")
	public List<WishlistItemView> list(
			@RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
		return service.list(includeArchived);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_WISHLIST')")
	public WishlistItemView get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_WISHLIST')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateWishlistItemRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", service.create(actor, request)));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_WISHLIST')")
	public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody UpdateWishlistItemRequest request) {
		service.update(id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_WISHLIST')")
	public ResponseEntity<Void> archive(@PathVariable UUID id) {
		service.archive(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/reorder")
	@PreAuthorize("hasAuthority('MANAGE_WISHLIST')")
	public ResponseEntity<Void> reorder(@Valid @RequestBody ReorderWishlistRequest request) {
		service.reorder(request.itemIds());
		return ResponseEntity.noContent().build();
	}
}
