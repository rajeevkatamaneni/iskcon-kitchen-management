package org.iskcon.kms.ban;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a temple may do with the ban records it raised (B9).
 *
 * <p><b>Read the list of what is not here.</b> There is no endpoint that searches the ban list, none
 * that reads a record by id, and none that counts them — for anybody, at any permission. The only
 * way a record another temple raised ever reaches a screen is as a {@link BanFinding} returned by an
 * actual hire, from {@code POST /api/v1/staff/members}. That is not an omission to be tidied up
 * later; it is the control that stops a cross-temple safeguard becoming a background-check service
 * that two hundred temples can query about anybody who ever worked in a kitchen. Adding a search
 * endpoint here would defeat the design however convenient it looked, and the integration test
 * asserts the absence deliberately.
 *
 * <p>Raising a record is not here either, and for the same kind of reason: it happens inside ending
 * somebody's employment ({@code POST /api/v1/staff/members/{id}/end-employment}), in that
 * transaction, because it is a decision made at a dismissal rather than an errand that can be run
 * against anybody at any time.
 *
 * <p>Everything below is {@code MANAGE_STAFF}. Hiring and dismissing are what this permission means,
 * and this is the gravest thing either of them leads to.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class EmploymentBanController {

	private final EmploymentBanService bans;

	public EmploymentBanController(EmploymentBanService bans) {
		this.bans = bans;
	}

	/** The category picklist, served rather than retyped in the browser where it would drift. */
	@GetMapping("/ban-categories")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public List<BanCategoryOption> categories() {
		return bans.categories();
	}

	/** The records <em>this</em> temple raised. There is no counterpart for anybody else's. */
	@GetMapping("/bans")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public List<EmploymentBanView> ours() {
		return bans.raisedByThisTemple();
	}

	/** Correcting the record — the owning temple only (KMS-4307). */
	@PutMapping("/bans/{id}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> amend(
			@PathVariable UUID id,
			@Valid @RequestBody RaiseEmploymentBanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		bans.amend(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Taking a record back. Its own sub-resource rather than a DELETE, because nothing is deleted:
	 * the record stays on file with the retraction on it and simply stops appearing at hires.
	 */
	@PostMapping("/bans/{id}/retraction")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> retract(
			@PathVariable UUID id,
			@RequestBody(required = false) RetractEmploymentBanRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		bans.retract(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * The admin saw findings and stopped. Recorded because it is the one outcome with no staff record
	 * to live on, and because it is the more responsible of the two answers — it should not be the
	 * one that leaves no trace.
	 */
	@PostMapping("/hire-checks/{checkId}/abandoned")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> abandoned(
			@PathVariable UUID checkId, @AuthenticationPrincipal AuthenticatedUser actor) {
		bans.recordAbandoned(actor, checkId);
		return ResponseEntity.noContent().build();
	}
}
