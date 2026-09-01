package org.iskcon.kms.staff;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conduct notes on one person's employment record (E6-S16).
 *
 * <p>Its own controller, and its own permission. Every other route under {@code /api/v1/staff} is
 * {@code MANAGE_STAFF} or {@code MANAGE_STAFF_SCHEDULE}; these two are
 * {@code MANAGE_STAFF_CONDUCT_NOTES}, which today only the Temple Admin holds. Somebody who may
 * hire, pay and dismiss still cannot read this unless they were given it separately, and that is the
 * point of it existing: the reading is the danger. The screen hides the panel from anybody without
 * it, but the screen is not the guard — these two annotations are.
 *
 * <p><b>There is no PUT and no DELETE, and neither is coming.</b> The table refuses both at the
 * database (V84), so a route offering either would be a button that cannot work. A note written in
 * error is answered by adding another.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffConductNoteController {

	private final StaffConductNoteService notes;

	public StaffConductNoteController(StaffConductNoteService notes) {
		this.notes = notes;
	}

	/** Every note on this person, newest first. */
	@GetMapping("/members/{id}/conduct-notes")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_CONDUCT_NOTES')")
	public List<StaffConductNoteView> list(@PathVariable UUID id) {
		return notes.notesFor(id);
	}

	/** Writes one, permanently, attributed to whoever is signed in. */
	@PostMapping("/members/{id}/conduct-notes")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_CONDUCT_NOTES')")
	public ResponseEntity<Map<String, Object>> add(
			@PathVariable UUID id,
			@Valid @RequestBody AddConductNoteRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", notes.add(actor, id, request)));
	}
}
