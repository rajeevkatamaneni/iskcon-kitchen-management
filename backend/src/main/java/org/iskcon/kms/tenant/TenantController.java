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
import org.springframework.web.bind.annotation.GetMapping;
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
	private final JdbcTemplate jdbc;

	public TenantController(TenantProvisioningService provisioningService, JdbcTemplate jdbc) {
		this.provisioningService = provisioningService;
		this.jdbc = jdbc;
	}

	@PostMapping
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public ResponseEntity<Map<String, Object>> provision(
			@Valid @RequestBody ProvisionTenantRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		UUID tenantId = provisioningService.provision(request, actor.getUserId());

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
}
