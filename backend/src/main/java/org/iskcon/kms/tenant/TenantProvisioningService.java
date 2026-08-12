package org.iskcon.kms.tenant;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings a temple onto the platform.
 *
 * <p>This is the dedicated, audited path SYSTEM_DESIGN.md §3 calls for. It is the one place in
 * the system that writes rows for a tenant other than the caller's own — the super-admin has no
 * tenant of their own, so ordinary tenant-scoped access cannot create one.
 *
 * <p>Uses JDBC rather than JPA on purpose. Both inserts happen outside any tenant context, which
 * is precisely the situation RLS is designed to prevent; doing it through the entity manager
 * would mean either weakening the policies or fighting them. Keeping it to explicit SQL in one
 * audited service makes the exception visible rather than hidden behind an abstraction.
 */
@Service
public class TenantProvisioningService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final org.iskcon.kms.calendar.CalendarPrecomputeScheduler calendarScheduler;
	private final org.iskcon.kms.occasion.OccasionService occasionService;
	private final org.iskcon.kms.meal.MealSlotService mealSlotService;

	public TenantProvisioningService(
			JdbcTemplate jdbc, AuditService auditService,
			org.iskcon.kms.calendar.CalendarPrecomputeScheduler calendarScheduler,
			org.iskcon.kms.occasion.OccasionService occasionService,
			org.iskcon.kms.meal.MealSlotService mealSlotService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.calendarScheduler = calendarScheduler;
		this.occasionService = occasionService;
		this.mealSlotService = mealSlotService;
	}

	/**
	 * Creates the temple and its first administrator as one unit.
	 *
	 * @return the new tenant's id
	 */
	@Transactional
	public UUID provision(ProvisionTenantRequest request, AuthenticatedUser actor) {
		validateTimezone(request.timezone());
		rejectDuplicateSlug(request.slug());

		UUID tenantId = insertTenant(request);

		// From here we genuinely act within the new temple. Establishing its context
		// transaction-locally is what lets the administrator insert pass RLS and what attributes
		// the audit event below to this tenant rather than to the tenantless super-admin. The
		// context is transaction-local, so it disappears at commit.
		establishTenantContext(tenantId);

		insertFirstAdministrator(request, tenantId);

		// A new temple starts with the common sattvic-prohibited items already flagged, so a
		// compliance failure can't happen simply because nobody remembered to mark garlic (E2-S1).
		seedProhibitedIngredients(tenantId);
		seedEkadashiProhibitedIngredients(tenantId);
		seedRecipeCategories(tenantId);

		// The common pan-ISKCON festival occasions, so the planner speaks the temple's festival
		// vocabulary from day one (E4-S2). Temple-specific occasions (anniversary, guru days) are
		// added by the temple.
		occasionService.seedForCurrentTenant();

		// Default meal slots (Lunch, Dinner, Deity Offering) so planning works out of the box (E4-S4).
		mealSlotService.seedForCurrentTenant();

		// The permanent record of provisioning, on the shared audit trail (E1-S7). before is null
		// — provisioning is a creation. The event belongs to this tenant, so a Temple Admin of the
		// new temple can later see that, and by whom, their temple was brought onto the platform.
		auditService.record(
				actor,
				AuditAction.TENANT_PROVISIONED,
				AuditEntityType.TENANT,
				tenantId,
				null,
				provisioningSnapshot(request, tenantId),
				null);

		// Queue the new temple's calendar so it's ready without waiting for the nightly sweep
		// (E4-S1). Best-effort: if no worker is attached, the sweep will build it.
		calendarScheduler.enqueueForTenant(tenantId);

		return tenantId;
	}

	private void validateTimezone(String timezone) {
		// Checked here rather than by annotation because the valid set is the JVM's zone
		// database, not a pattern. A wrong timezone silently shifts every Ekadashi calculation
		// for that temple, so it is worth failing loudly at provisioning time.
		try {
			ZoneId.of(timezone);
		} catch (Exception e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "timezone", "value", timezone),
					e);
		}
	}

	private void rejectDuplicateSlug(String slug) {
		Integer existing = jdbc.queryForObject(
				"SELECT count(*) FROM tenants WHERE slug = ?", Integer.class, slug);

		if (existing != null && existing > 0) {
			throw new ApplicationException(
					ErrorCode.SLUG_ALREADY_TAKEN,
					Map.of("slug", slug));
		}
	}

	private UUID insertTenant(ProvisionTenantRequest request) {
		return jdbc.queryForObject("""
				INSERT INTO tenants (
					slug, name, address, latitude, longitude, timezone,
					currency, locale, is_80g_approved)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""",
				UUID.class,
				request.slug(),
				request.name(),
				request.address(),
				request.latitude(),
				request.longitude(),
				request.timezone(),
				request.currency(),
				"en-IN",
				request.is80gApproved());
	}

	/**
	 * Establishes the new tenant's context for the rest of this transaction. The users table and
	 * audit_events are both RLS-protected, and provisioning runs as the super-admin, who has no
	 * tenant — so without this the inserts are refused by the database. That refusal is isolation
	 * working correctly, not an obstacle to route around; setting the context is the honest fix,
	 * because at this moment we genuinely are acting within that tenant.
	 *
	 * <p>The third argument to set_config makes it transaction-local: it disappears at commit and
	 * cannot leak into the next thing this pooled connection does.
	 */
	private void establishTenantContext(UUID tenantId) {
		jdbc.queryForObject(
				"SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
	}

	private void insertFirstAdministrator(ProvisionTenantRequest request, UUID tenantId) {
		// firebase_uid is a placeholder until this person first signs in. They exist as a
		// Temple Admin here before they have ever authenticated with Firebase — which is the
		// right way round: the temple decides who administers it, not whoever signs up first.
		String pendingUid = "pending:" + UUID.randomUUID();

		try {
			jdbc.update("""
					INSERT INTO users (
						tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, ?, ?, ?, ?, 'TEMPLE_ADMIN', 'ACTIVE')
					""",
					tenantId,
					pendingUid,
					request.adminName(),
					request.adminEmail().toLowerCase(),
					request.adminPhone());

		} catch (org.springframework.dao.DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.EMAIL_ALREADY_REGISTERED,
					Map.of("email", request.adminEmail(), "tenantId", tenantId),
					e);
		}
	}

	/**
	 * The sattvic-prohibited items every temple should start with flagged (E2-S1). Deliberately the
	 * canonical short list — onion, garlic, mushroom, egg — which a Temple Admin then extends. Runs
	 * inside the new tenant's transaction-local context, so the RLS write policy admits the inserts.
	 */
	private void seedProhibitedIngredients(UUID tenantId) {
		String[][] seed = {
			{"Onion", "Vegetables", "KG"},
			{"Garlic", "Vegetables", "KG"},
			{"Mushroom", "Vegetables", "KG"},
			{"Egg", "Other", "PIECES"},
		};
		for (String[] item : seed) {
			jdbc.update("""
					INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_sattvic_prohibited)
					VALUES (?, ?, ?, ?, true)
					""", tenantId, item[0], item[1], item[2]);
		}
	}

	/**
	 * The Ekadashi-prohibited staples every temple should start with flagged (E4-S6): the common
	 * grains and beans. A Temple Admin extends the list. Same tenant-local context as above.
	 */
	private void seedEkadashiProhibitedIngredients(UUID tenantId) {
		String[][] seed = {
			{"Rice", "Grains", "KG"},
			{"Wheat Flour", "Grains", "KG"},
			{"Semolina", "Grains", "KG"},
			{"Toor Dal", "Pulses", "KG"},
			{"Moong Dal", "Pulses", "KG"},
			{"Chana Dal", "Pulses", "KG"},
			{"Urad Dal", "Pulses", "KG"},
		};
		for (String[] item : seed) {
			jdbc.update("""
					INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_ekadashi_prohibited)
					VALUES (?, ?, ?, ?, true)
					ON CONFLICT (tenant_id, lower(name)) DO NOTHING
					""", tenantId, item[0], item[1], item[2]);
		}
	}

	/**
	 * The recipe categories every temple starts with (E2-S2), drawn from RM 2019's sheet list.
	 * Ekadashi is flagged fasting-compatible — E4 consumes that to know a recipe is allowed on a
	 * fasting day. A Temple Admin adds more.
	 */
	private void seedRecipeCategories(UUID tenantId) {
		String[] ordinary = {
			"Beverages", "Breakfast", "Rice", "Dal", "Sabji", "Roti", "Sweets", "Snacks",
		};
		for (String name : ordinary) {
			jdbc.update("""
					INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
					VALUES (?, ?, false)
					""", tenantId, name);
		}
		jdbc.update("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Ekadashi', true)
				""", tenantId);
	}

	/**
	 * The after-state recorded on the provisioning audit event: what the temple and its first
	 * administrator were created as. A LinkedHashMap so the JSONB reads in a sensible order.
	 * Nothing secret goes in — this is the same information the tenant list already shows.
	 */
	private Map<String, Object> provisioningSnapshot(ProvisionTenantRequest request, UUID tenantId) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("tenantId", tenantId.toString());
		snapshot.put("slug", request.slug());
		snapshot.put("name", request.name());
		snapshot.put("timezone", request.timezone());
		snapshot.put("currency", request.currency());
		snapshot.put("is80gApproved", request.is80gApproved());
		snapshot.put("firstAdminName", request.adminName());
		snapshot.put("firstAdminEmail", request.adminEmail().toLowerCase());
		return snapshot;
	}
}
