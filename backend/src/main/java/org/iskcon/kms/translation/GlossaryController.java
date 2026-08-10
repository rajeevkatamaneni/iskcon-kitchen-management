package org.iskcon.kms.translation;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The translation glossary (E2-S6), behind {@code MANAGE_RECIPES}. */
@RestController
@RequestMapping("/api/v1/translation-glossary")
public class GlossaryController {

	private final GlossaryService glossaryService;

	public GlossaryController(GlossaryService glossaryService) {
		this.glossaryService = glossaryService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public List<GlossaryEntryView> list(@RequestParam(name = "language", required = false) String language) {
		return glossaryService.list(language);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody AddGlossaryEntryRequest request) {
		UUID id = glossaryService.upsert(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		glossaryService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
