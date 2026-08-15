package org.iskcon.kms.tenant;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Choosing where you serve (E1-S16).
 *
 * <p>A devotee signing in with Google is somebody before they are anybody's member. These two
 * endpoints are the whole of what they may do until they have chosen: see which temples are on the
 * platform, and join one as a volunteer. Everything else on the platform belongs to a temple.
 */
@RestController
public class MembershipController {

	private final MembershipService memberships;

	public MembershipController(MembershipService memberships) {
		this.memberships = memberships;
	}

	/**
	 * The temples a person may join. Readable by anyone signed in — including someone who belongs to
	 * no temple yet, which is the point. It carries a temple's name and address and nothing else: what
	 * a person needs to recognise their own temple, and nothing about how it is run.
	 */
	@GetMapping("/api/v1/temples")
	@PreAuthorize("isAuthenticated()")
	public List<TempleSummary> list() {
		return memberships.templesToJoin();
	}

	/**
	 * Join a temple as a volunteer. This is the one place in the product where the tenant comes from
	 * the request rather than from a verified record — because the request is the person choosing
	 * one. It is narrowed to match: it can only add the caller's own membership, only as a
	 * VOLUNTEER, and only where they have none.
	 */
	@PostMapping("/api/v1/temples/{templeId}/join")
	@PreAuthorize("hasAuthority('JOIN_A_TEMPLE') or isAuthenticated()")
	public ResponseEntity<Map<String, Object>> join(
			@PathVariable UUID templeId,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = memberships.join(actor, templeId);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", id, "tenantId", templeId));
	}

	/** A temple as a devotee picking one would recognise it: its name and where it is. */
	public record TempleSummary(UUID id, String name, String address) {
	}
}
