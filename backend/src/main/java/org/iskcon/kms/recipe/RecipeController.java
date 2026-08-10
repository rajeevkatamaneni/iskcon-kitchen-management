package org.iskcon.kms.recipe;

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
 * Recipes (E2-S2), all behind {@code MANAGE_RECIPES}. Browse and detail read; create/edit/archive
 * write, and every write is audited. Archiving is a soft delete — the recipe stays renderable.
 */
@RestController
@RequestMapping("/api/v1/recipes")
public class RecipeController {

	private final RecipeService recipeService;

	public RecipeController(RecipeService recipeService) {
		this.recipeService = recipeService;
	}

	/** Browse/search: filter by category, by contained ingredient ("what can we make with X"), or name. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<RecipeSummary> list(
			@RequestParam(name = "categoryId", required = false) UUID categoryId,
			@RequestParam(name = "ingredientId", required = false) UUID ingredientId,
			@RequestParam(name = "q", required = false) String query,
			@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived,
			@RequestParam(name = "ekadashiCompatible", defaultValue = "false") boolean ekadashiCompatibleOnly) {
		return recipeService.list(categoryId, ingredientId, query, includeArchived, ekadashiCompatibleOnly);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public RecipeView get(@PathVariable UUID id) {
		return recipeService.get(id);
	}

	/** The recipe scaled to a target yield (E2-S3). Computed on demand; nothing is stored. */
	@GetMapping("/{id}/scaled")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ScaledRecipeView scale(
			@PathVariable UUID id,
			@RequestParam("targetYield") java.math.BigDecimal targetYield) {
		return recipeService.scale(id, targetYield);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateRecipeRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = recipeService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateRecipeRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		recipeService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/** Archive (soft delete). Kept renderable because a meal plan may reference it (E4). */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> archive(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		recipeService.archive(actor, id);
		return ResponseEntity.noContent().build();
	}
}
