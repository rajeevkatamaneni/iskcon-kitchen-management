package org.iskcon.kms.tenant;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
	private final TenantUpdateService updateService;
	private final TenantDeletionService deletionService;
	private final TenantExportService exportService;
	private final JdbcTemplate jdbc;

	public TenantController(
			TenantProvisioningService provisioningService,
			TenantUpdateService updateService,
			TenantDeletionService deletionService,
			TenantExportService exportService,
			JdbcTemplate jdbc) {
		this.provisioningService = provisioningService;
		this.updateService = updateService;
		this.deletionService = deletionService;
		this.exportService = exportService;
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
	 * name, when it was created, how many people have accounts — and nothing about what happens
	 * inside it.
	 *
	 * <p>The headcount comes from {@code tenant_user_count} rather than from a subselect over
	 * {@code users}, and the reason is worth reading before anyone simplifies it back. See T-061 and
	 * {@code V102__operator_temple_member_count.sql}: {@code users} is tenant-owned and carries
	 * {@code FORCE ROW LEVEL SECURITY}, the operator is tenantless, and so a direct count returned 0
	 * for every temple, forever, without erroring. The function adopts one temple's context
	 * transaction-locally — the same move {@code delete_tenant_cascade} makes — counts inside the
	 * policy rather than around it, and puts the caller's context back before returning. It answers
	 * with a number and nothing else, which is what keeps an operator on the right side of D-13.
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
					t.created_at,
					tenant_user_count(t.id) AS user_count
				FROM tenants t
				ORDER BY t.created_at DESC
				""");
	}

	/**
	 * One temple's details, for the view page — and, since T-008, for the correction screen that
	 * has to open on what the record already says.
	 *
	 * <p>Coordinates are here for that second reader alone. The view page has never shown a
	 * latitude, and a pair of six-decimal numbers is not something a person reads; but they are two
	 * of the seven fields {@code PATCH} accepts, and a correction screen that could not prefill them
	 * would either make an operator retype coordinates they did not come to change, or — far
	 * worse — send zeroes for them and move the temple's sunrise to the Gulf of Guinea.
	 */
	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public Map<String, Object> get(@PathVariable UUID id) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT
					t.id,
					t.slug,
					t.name,
					t.address,
					t.latitude,
					t.longitude,
					t.timezone,
					t.currency,
					t.is_80g_approved,
					t.created_at,
					-- Not a subselect over users: that one is filtered to nothing for a tenantless
					-- operator and answers 0 for every temple. Same reason as in list() above,
					-- T-061 / V102.
					tenant_user_count(t.id) AS user_count,
					-- When this temple was last exported, so the screen can say so and keep the
					-- delete action shut until a copy exists (E1-S15, D6). This one needs no such
					-- help: platform_audit_events is a platform table, not a tenant-owned one, and
					-- its policy admits a verified super-admin directly. That contrast is the rule —
					-- an operator reading a table with a tenant_id column needs a tenant context.
					(SELECT max(p.created_at) FROM platform_audit_events p
						WHERE p.action = 'TENANT_EXPORTED' AND p.entity_id = t.id) AS last_export_at
				FROM tenants t
				WHERE t.id = ?
				""", id);

		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", id));
		}
		return rows.get(0);
	}

	/**
	 * Corrects a temple's profile, and records its 80G approval (T-008, docket A1 + A2).
	 *
	 * <p>The endpoint this controller went without. Everything below the provisioning insert was
	 * write-once: a temple typed in wrongly could be fixed only by deleting it, and 80G approval —
	 * which arrives from the Income Tax department long after a temple starts using the product —
	 * could not be recorded at any point afterwards, so receipts stayed wrong.
	 *
	 * <p>Behind {@code MANAGE_TENANTS} and not a permission of its own, and behind the operator's
	 * rather than the temple's: D-13, ruled 2026-09-07. See {@link TenantUpdateService} for what
	 * that ruling costs and buys.
	 *
	 * <p>{@code PATCH} rather than {@code PUT} because it addresses a subset of the row — the slug,
	 * the locale, the status and the timestamps are not the caller's to send. The body itself is not
	 * partial: every field it names is required. Returns 204, because the caller already knows what
	 * it sent and the screen returns to the temple's page to read it back.
	 */
	@PatchMapping("/{id}")
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateTenantRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		updateService.update(id, request, actor);
		return ResponseEntity.noContent().build();
	}

	/**
	 * A complete copy of one temple's data as a workbook (E1-S15). Behind {@code DELETE_TENANT}
	 * rather than {@code MANAGE_TENANTS}: it exists to make deletion survivable, and it hands over
	 * the temple's entire business in one file, so it belongs with the graver permission.
	 */
	@GetMapping("/{id}/export")
	@PreAuthorize("hasAuthority('DELETE_TENANT')")
	public ResponseEntity<byte[]> export(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {

		TenantExportService.Export export = exportService.export(id, actor);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(export.filename()))
				.contentType(MediaType.parseMediaType(
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.body(export.content());
	}

	/**
	 * The download header for an export.
	 *
	 * <p>Supplying a charset makes Spring encode the plain {@code filename} too, which turns an
	 * ordinary English temple name into {@code =?UTF-8?Q?Sri_Sri...?=} for no gain. So a name that is
	 * already ASCII is sent as itself, and only a name that needs it — a temple written in Devanagari
	 * or Kannada — gets the encoded form, which every current browser reads correctly.
	 */
	private static String contentDisposition(String filename) {
		ContentDisposition.Builder builder = ContentDisposition.attachment();
		return (StandardCharsets.US_ASCII.newEncoder().canEncode(filename)
				? builder.filename(filename)
				: builder.filename(filename, StandardCharsets.UTF_8))
				.build()
				.toString();
	}

	/**
	 * Permanently deletes a temple and all of its data. Behind {@code DELETE_TENANT} — a graver
	 * capability than provisioning, held separately so it can be granted on its own. Refused unless
	 * a data export was taken recently (E1-S15, D6).
	 */
	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('DELETE_TENANT')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		deletionService.delete(id, actor);
		return ResponseEntity.noContent().build();
	}
}
