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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recipe categories (E2-S2), behind {@code MANAGE_RECIPES}. The seeded list, plus tenant additions. */
@RestController
@RequestMapping("/api/v1/recipe-categories")
public class RecipeCategoryController {

	private final RecipeCategoryService categoryService;

	public RecipeCategoryController(RecipeCategoryService categoryService) {
		this.categoryService = categoryService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<RecipeCategoryView> list() {
		return categoryService.list();
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateCategoryRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = categoryService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}
}
