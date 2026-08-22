package org.iskcon.kms.library;

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
 * The shared recipe library (E2-S9, E2-S15).
 *
 * <p>Two audiences on one resource, and the split is by verb rather than by route. Reading is
 * behind {@code MANAGE_RECIPES}, which the three kitchen roles hold: a cook browsing for something
 * to make is the point of the library existing. Writing is behind
 * {@code MANAGE_RECIPE_LIBRARY}, which only a platform operator holds, because an edit here changes
 * what every temple on the platform is offered.
 *
 * <p>Both are enforced again by the RLS policies in V68. A permission gives a clear refusal; the
 * policy is what makes the refusal true even if this annotation were ever wrong.
 */
@RestController
@RequestMapping("/api/v1/library/recipes")
public class MasterRecipeController {

	private final MasterRecipeService service;
	private final LibraryLoader loader;

	public MasterRecipeController(MasterRecipeService service, LibraryLoader loader) {
		this.service = service;
		this.loader = loader;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES') or hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public List<MasterRecipeSummary> list(
			@RequestParam(name = "q", required = false) String query,
			@RequestParam(name = "state", required = false) String stateSlug,
			@RequestParam(name = "category", required = false) String categoryKey,
			@RequestParam(name = "limit", defaultValue = "40") int limit) {

		if (query != null && !query.isBlank()) {
			return service.search(query, limit);
		}
		return service.browse(stateSlug, categoryKey, limit);
	}

	@GetMapping("/states")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES') or hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public List<Map<String, Object>> states() {
		return service.states();
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES') or hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public MasterRecipeView get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody MasterRecipeInput input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", service.create(actor, input)));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody MasterRecipeInput input,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.update(actor, id, input);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		service.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Reads the vendored books into the library.
	 *
	 * <p>Idempotent, so it is safe to press twice: the upsert keys on the book and the recipe slug.
	 * Exposed as an endpoint as well as a startup job because loading a fresh environment should not
	 * need a separate deployment to do it.
	 */
	@PostMapping("/load")
	@PreAuthorize("hasAuthority('MANAGE_RECIPE_LIBRARY')")
	public LibraryLoader.Result load() {
		return loader.load();
	}
}
