package org.iskcon.kms.translation;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Recipe translation (E2-S6), behind {@code MANAGE_RECIPES}. Translates on first request, then caches. */
@RestController
public class RecipeTranslationController {

	private final RecipeTranslationService translationService;

	public RecipeTranslationController(RecipeTranslationService translationService) {
		this.translationService = translationService;
	}

	@GetMapping("/api/v1/recipes/{id}/translations/{language}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public TranslatedRecipeView translate(@PathVariable UUID id, @PathVariable String language) {
		return translationService.view(id, language);
	}
}
