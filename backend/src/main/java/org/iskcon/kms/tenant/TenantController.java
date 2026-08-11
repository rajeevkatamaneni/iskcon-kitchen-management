package org.iskcon.kms.tenant;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform administration. Every endpoint here is super-admin only.
 *
 * <p>Note what is absent: no endpoint to read a temple's recipes, donations, or inventory. The
 * super-admin provisions temples and can see that they exist and are healthy — running the
 * platform is not the same as running a temple, and that boundary is enforced by the permission
 * model rather than by convention.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

	private final TenantProvisioningService provisioningService;
	private final TenantDeletionService deletionService;
	private final JdbcTemplate jdbc;

	public TenantController(
			TenantProvisioningService provisioningService,
			TenantDeletionService deletionService,
			JdbcTemplate jdbc) {
		this.provisioningService = provisioningService;
		this.deletionService = deletionService;
		this.jdbc = jdbc;
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public ResponseEntity<Map<String, Object>> provision(
			@Valid @RequestBody ProvisionTenantRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID tenantId = provisioningService.provision(request, actor);

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", tenantId, "slug", request.slug()));
	}

	/**
	 * Read-only list for release 1. Shows enough to confirm a temple exists and is being used —
	 * name, status, when it was created, how many people have accounts — and nothing about what
	 * happens inside it.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public List<Map<String, Object>> list() {
		return jdbc.queryForList("""
				SELECT
					t.id,
					t.slug,
					t.name,
					t.timezone,
					t.currency,
					t.is_80g_approved,
					t.status,
					t.created_at,
					(SELECT count(*) FROM users u WHERE u.tenant_id = t.id) AS user_count
				FROM tenants t
				ORDER BY t.created_at DESC
				""");
	}

	/** One temple's details, for the view page. Same shape as a list row. */
	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public Map<String, Object> get(@PathVariable UUID id) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT
					t.id,
					t.slug,
					t.name,
					t.address,
					t.timezone,
					t.currency,
					t.is_80g_approved,
					t.status,
					t.created_at,
					(SELECT count(*) FROM users u WHERE u.tenant_id = t.id) AS user_count
				FROM tenants t
				WHERE t.id = ?
				""", id);

		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", id));
		}
		return rows.get(0);
	}

	/**
	 * Permanently deletes a temple and all of its data. Behind {@code DELETE_TENANT} — a graver
	 * capability than provisioning, held separately so it can be granted on its own.
	 */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('DELETE_TENANT')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		deletionService.delete(id, actor);
		return ResponseEntity.noContent().build();
	}
}
