package org.iskcon.kms.ingredient;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ingredient catalogue (E2-S1). Descriptive CRUD is behind {@code MANAGE_RECIPES} — ordinary
 * kitchen work — while the Ekadashi-prohibited flag has its own endpoint behind
 * {@code MANAGE_DIETARY_POLICY}, a Temple Admin only, because deciding what is prohibited is a
 * religious-compliance decision, not routine editing. Every write is on the audit trail.
 *
 * <p>{@code PATCH /{id}/sattvic-flag} stood beside the Ekadashi one until 2026-09-08, when D-18
 * deleted the sattvic-prohibited flag outright. Nothing is left to set, so the route is gone rather
 * than kept as a no-op that would report success while doing nothing.
 *
 * <p>The supply flag (D-1) gets neither a route nor a permission of its own, deliberately, and the
 * Ekadashi flag beside it is exactly the shape it was not copied from. That split exists because
 * declaring an ingredient prohibited is a religious-compliance decision; saying a thing is a mop is
 * not. So {@code supply} arrives on the create and update bodies with the name and the category, and
 * a Kitchen Manager who can rename an ingredient can also say it is a supply.
 */
@RestController
@RequestMapping("/api/v1/ingredients")
public class IngredientController {

	private final IngredientService ingredientService;

	public IngredientController(IngredientService ingredientService) {
		this.ingredientService = ingredientService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<IngredientView> list() {
		return ingredientService.list();
	}

	/** Name/alias typeahead for recipe and inventory pickers. */
	@GetMapping("/search")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<IngredientSummary> search(@RequestParam(name = "q", required = false) String query) {
		return ingredientService.search(query);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public IngredientView get(@PathVariable UUID id) {
		return ingredientService.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateIngredientRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = ingredientService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateIngredientRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		ingredientService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/** The Ekadashi-prohibited flag: Temple Admin only, always audited (E4-S6). */
	@PatchMapping("/{id}/ekadashi-flag")
	// The authority is a STRING here, not the enum constant, so nothing checks it against
	// Permission — a rename that misses this line compiles, deploys, and 403s every Temple Admin
	// because it names a permission nobody holds. Renamed with the constant on 2026-09-08 (D-21);
	// what keeps it honest is IngredientIT.onlyAdminChangesEkadashiFlag, which goes red if the two
	// ever drift apart. Keep them in step, and keep that test.
	@PreAuthorize("hasAuthority('MANAGE_DIETARY_POLICY')")
	public ResponseEntity<Void> setEkadashiFlag(
			@PathVariable UUID id,
			@Valid @RequestBody SetEkadashiFlagRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		ingredientService.setEkadashiFlag(actor, id, request.ekadashiProhibited());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {

		ingredientService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}
}
