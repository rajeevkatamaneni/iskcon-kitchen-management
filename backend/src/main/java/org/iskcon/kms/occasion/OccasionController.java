package org.iskcon.kms.occasion;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.format.annotation.DateTimeFormat;
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
 * The festival occasion catalog (E4-S2). Planners read the catalog and the resolved occurrences
 * ({@code MANAGE_MEAL_PLANS}); curating the catalog is a temple-settings decision, so writes are
 * {@code MANAGE_TEMPLE_SETTINGS} — a Temple Admin.
 */
@RestController
@RequestMapping("/api/v1/occasions")
public class OccasionController {

	private final OccasionService occasionService;

	public OccasionController(OccasionService occasionService) {
		this.occasionService = occasionService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<OccasionView> list() {
		return occasionService.list();
	}

	/** Occasions resolved to concrete dates within a range — what the planner overlays. */
	@GetMapping("/resolved")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<ResolvedOccasion> resolved(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		return occasionService.resolve(from, to);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateOccasionRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = occasionService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateOccasionRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		occasionService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {

		occasionService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}
}
