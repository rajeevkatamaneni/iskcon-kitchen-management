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
	private final RecipeSearchService searchService;
	private final org.iskcon.kms.library.RecipeImportService importService;

	public RecipeController(
			RecipeService recipeService,
			RecipeSearchService searchService,
			org.iskcon.kms.library.RecipeImportService importService) {
		this.recipeService = recipeService;
		this.searchService = searchService;
		this.importService = importService;
	}

	/**
	 * The Recipes page's single box, over the temple's own recipes and the shared library together
	 * (E2-S10). An empty query returns the temple's own active recipes and no library rows.
	 */
	@GetMapping("/search")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<RecipeSearchResult> search(@RequestParam(name = "q", required = false) String query) {
		return searchService.search(query);
	}

	/**
	 * Takes this temple's own copy of a library recipe (E2-S12).
	 *
	 * <p>The id in the path is the <em>library</em> recipe's; the id that comes back is the temple's
	 * new one. Refused with KMS-4968 if they already hold it, KMS-4905 if the name is taken, and
	 * KMS-4970 if it needs an ingredient the temple has flagged — in every case having written
	 * nothing at all.
	 */
	@PostMapping("/import/{masterRecipeId}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> importFromLibrary(
			@PathVariable UUID masterRecipeId,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		var imported = importService.importRecipe(actor, masterRecipeId);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
				"id", imported.recipeId(),
				"name", imported.name(),
				"ingredientsCreated", imported.ingredientsCreated(),
				"categoryCreated", imported.categoryCreated()));
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

	/**
	 * Archive. Kept renderable because a meal plan may reference it (E4), and reversible by
	 * {@link #restore} — a temple that archives the wrong recipe should not need support to undo it.
	 */
	@PostMapping("/{id}/archive")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> archive(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		recipeService.archive(actor, id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/restore")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> restore(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		recipeService.restore(actor, id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Delete outright — refused with KMS-4967 for a recipe any meal plan has ever named, which is
	 * told to archive instead. DELETE used to archive; it now does what the verb says, and archiving
	 * has a URL that says what it is.
	 */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		recipeService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}
}
