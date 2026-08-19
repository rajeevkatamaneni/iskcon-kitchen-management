package org.iskcon.kms.communication;

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
 * Writing to the temple's community (E8-S2, E8-S3), behind {@code MANAGE_COMMUNICATIONS}.
 *
 * <p>The order of the endpoints is the order of the work: write it, look at it, send it to yourself,
 * count who would get it, and only then send. Nothing before the last one touches a devotee.
 */
@RestController
@RequestMapping("/api/v1/communications")
public class CommunicationController {

	private final CommunicationService service;

	public CommunicationController(CommunicationService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public List<CommunicationView> list() {
		return service.list();
	}

	/** The categories a temple admin may write in — never the operational one. */
	@GetMapping("/categories")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public List<Map<String, Object>> categories() {
		return CommunicationCategory.composable().stream()
				.map(c -> Map.<String, Object>of(
						"value", c.name(), "label", c.label(), "description", c.description()))
				.toList();
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public CommunicationView get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody SaveCommunicationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", service.save(actor, null, request)));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody SaveCommunicationRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		service.save(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

	/** What will actually arrive, built by the same code that builds the message itself. */
	@GetMapping("/{id}/preview")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public CommunicationService.PreviewView preview(@PathVariable UUID id) {
		return service.preview(id);
	}

	/** How many devotees would receive it right now — the number the confirmation shows. */
	@GetMapping("/{id}/audience")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public Map<String, Object> audience(@PathVariable UUID id) {
		return Map.of("count", service.audienceSize(id));
	}

	/** One copy, to whoever is signed in. The honest answer to "what will it look like in Gmail?". */
	@PostMapping("/{id}/test")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public ResponseEntity<Void> test(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		service.sendTest(actor, id);
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/{id}/send")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public ResponseEntity<CommunicationService.SendResultView> send(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.ok(service.send(actor, id));
	}

	/** Who it went to, and what became of each — the answer to "did it actually go?". */
	@GetMapping("/{id}/deliveries")
	@PreAuthorize("hasAuthority('MANAGE_COMMUNICATIONS')")
	public List<CommunicationService.DeliveryView> deliveries(@PathVariable UUID id) {
		return service.deliveries(id);
	}

}
