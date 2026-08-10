package org.iskcon.kms.user;

import jakarta.validation.Valid;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing the people in a temple. For now just the one action the audit framework needed a real
 * before/after for — changing a role. The rest of user management (invite, disable) is E1-S12,
 * built on this same seam.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final RoleChangeService roleChangeService;

	public UserController(RoleChangeService roleChangeService) {
		this.roleChangeService = roleChangeService;
	}

	/**
	 * Changes a user's role. Returns 204 on success; the guards in {@link RoleChangeService} decide
	 * what is refused, and every outcome — applied or refused — is on the audit trail.
	 */
	@PatchMapping("/{id}/role")
	@PreAuthorize("hasAuthority('MANAGE_USERS')")
	public ResponseEntity<Void> changeRole(
			@PathVariable UUID id,
			@Valid @RequestBody ChangeRoleRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		roleChangeService.changeRole(actor, id, request.role());
		return ResponseEntity.noContent().build();
	}
}
