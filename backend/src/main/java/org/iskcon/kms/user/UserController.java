package org.iskcon.kms.user;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing the people in a temple (E1-S12): list them, add them, change a role, disable or restore
 * one. Every endpoint is behind {@code MANAGE_USERS}, and every action is on the audit trail.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserManagementService userManagementService;
	private final RoleChangeService roleChangeService;

	public UserController(
			UserManagementService userManagementService, RoleChangeService roleChangeService) {
		this.userManagementService = userManagementService;
		this.roleChangeService = roleChangeService;
	}

	/** The people at the acting admin's temple. RLS scopes it to their temple. */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_USERS')")
	public List<UserSummary> list() {
		return userManagementService.listUsers();
	}

	/** Adds a person to the temple. They claim the account on first sign-in (E1-S6). */
	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_USERS')")
	public ResponseEntity<Map<String, Object>> add(
			@Valid @RequestBody AddUserRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID id = userManagementService.addUser(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * Changes a user's role. The guards live in {@link RoleChangeService}, and every outcome —
	 * applied or refused — is on the audit trail.
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

	/** Disables or re-enables a user. Disabling blocks access on their next request (E1-S4). */
	@PatchMapping("/{id}/status")
	@PreAuthorize("hasAuthority('MANAGE_USERS')")
	public ResponseEntity<Void> setStatus(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateStatusRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		userManagementService.setStatus(actor, id, request.status());
		return ResponseEntity.noContent().build();
	}
}
