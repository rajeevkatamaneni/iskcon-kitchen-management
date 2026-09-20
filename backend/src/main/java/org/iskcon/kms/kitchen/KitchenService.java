package org.iskcon.kms.kitchen;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The kitchens a temple runs (E10-S2): the Deity kitchen, the prasadam kitchen, the restaurant, the
 * Food-for-Life kitchen. They are peers under the tenant — there is no kitchen inside another one
 * (design D1) — and every action runs in the acting user's tenant context, so RLS confines it to
 * their own temple and a kitchen belonging to another is simply not found.
 *
 * <p><strong>Who runs the kitchen is validated in the application, not left to the foreign key.</strong>
 * An FK check runs as the table owner and is not subject to RLS, so {@code in_charge_user_id} naming
 * a person at another temple would satisfy the constraint and quietly bind a stranger to this
 * temple's kitchen. {@link #resolveInCharge} looks the user up at this temple first, so an id that is
 * not this temple's is rejected as unknown — the same defence {@code RecipeService.resolveCategory}
 * makes for its category. For this table the temple is named in the query rather than left to RLS,
 * because the users policy also shows a caller their own accounts at other temples (T-190).
 *
 * <p><strong>Exactly one main kitchen, and the database is what guarantees it.</strong>
 * {@code kitchens_one_main_per_tenant} is a partial unique index, so no sequence of application
 * mistakes can leave a temple with two. This service's job is to make the ordinary path never reach
 * it: a temple's first kitchen is created main because there is nothing for the flag to sit on, and
 * marking a second kitchen main clears the previous one in the same transaction, so the two acts
 * can never be half-done. Two administrators moving the flag in the same instant is the one case
 * the application cannot order for them, and there the index refuses one of them outright rather
 * than letting a second main through — see {@link #onDuplicate}.
 *
 * <p><strong>Delete when nothing points at it, archive when something does.</strong> A kitchen named
 * on six months of issued requests cannot be deleted without hollowing that history out, and
 * {@code ingredient_requests.kitchen_id} is {@code ON DELETE RESTRICT} precisely so it cannot be.
 * The pattern is the one {@code IngredientService.delete} already set.
 */
@Service
public class KitchenService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final MealPlannerAdoption mealPlannerAdoption;
	private final KitchenOrder kitchenOrder;

	public KitchenService(
			JdbcTemplate jdbc, AuditService auditService, MealPlannerAdoption mealPlannerAdoption,
			KitchenOrder kitchenOrder) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.mealPlannerAdoption = mealPlannerAdoption;
		this.kitchenOrder = kitchenOrder;
	}

	/**
	 * Makes sure the current temple has a kitchen that plans its meals, and returns it (Epic 12, T-350).
	 *
	 * <p>Called by provisioning beside the meal-kind seed, so a brand-new temple can plan its first meal:
	 * every meal now has at least one kitchen and every staff member belongs to one, and a temple with
	 * no kitchen could do neither. There it creates 'Main kitchen', marked main and using the meal
	 * planner.
	 *
	 * <p>Where the temple already has one, it is returned and nothing is written — the same choice
	 * {@link KitchenOrder#defaultPlanningKitchen} makes for somebody with no kitchen of their own: the
	 * main kitchen if it plans meals, else the first planner kitchen in Settings order. Where it has none,
	 * the rules are V150's, so a temple migrated and a temple provisioned end up the same: named 'Main
	 * kitchen', or 'Main kitchen (meals)' when that is taken (names compare case-insensitively); main only
	 * if the temple has no main kitchen, because moving the title off a kitchen the temple chose is not
	 * a side effect anybody asked for; and never by turning the planner on for an existing kitchen,
	 * which would settle that kitchen's open ingredient requests ({@link MealPlannerAdoption}).
	 *
	 * <p>Runs in the caller's tenant context and transaction. Not audited on its own: at provisioning the
	 * temple's creation is the audited event, and the interim callers in the meal and staff services
	 * (until T-354/T-357) audit the save that needed it.
	 *
	 * @param createdBy who the kitchen is recorded as created by, if one has to be made
	 * @return the temple's default planning kitchen
	 */
	@Transactional
	public UUID seedMainKitchenForCurrentTenant(UUID createdBy) {
		Optional<UUID> existing = kitchenOrder.defaultPlanningKitchen(null);
		if (existing.isPresent()) {
			return existing.get();
		}
		String name = "Main kitchen";
		for (int attempt = 1; nameTaken(name); attempt++) {
			name = attempt == 1 ? "Main kitchen (meals)" : "Main kitchen (meals " + attempt + ")";
		}
		Integer mains = jdbc.queryForObject("SELECT count(*) FROM kitchens WHERE is_main", Integer.class);
		boolean main = mains == null || mains == 0;

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchens (id, tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, true, 'ACTIVE', ?)
				""", id, name, main, createdBy);
		return id;
	}

	/** Whether a kitchen of this temple already has this name, compared as kitchens_name_per_tenant does. */
	private boolean nameTaken(String name) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM kitchens WHERE lower(name) = lower(?)", Integer.class, name);
		return n != null && n > 0;
	}

	/**
	 * The temple's kitchens, the main one first and the rest by name — which is the order the
	 * register is read in, and the order a picker wants.
	 *
	 * <p>Archived ones are left out unless asked for. They are still in the list somebody restores
	 * from, and still on the requests they were named on, but a kitchen that has been closed should
	 * not be an option when a cook is choosing where the food is going.
	 */
	@Transactional(readOnly = true)
	public List<KitchenView> list(boolean includeArchived) {
		String sql = """
				SELECT k.id, k.name, k.description, k.location, k.is_main, k.uses_meal_planner,
					   k.in_charge_user_id, u.full_name AS in_charge_name, k.contact_phone,
					   k.status, k.created_at,
					   (SELECT count(*) FROM staff_profiles sp
						WHERE sp.kitchen_id = k.id AND sp.employment_status = 'ACTIVE') AS staff_count
				FROM kitchens k
				LEFT JOIN users u ON u.id = k.in_charge_user_id
				""" + (includeArchived ? "" : "WHERE k.status = 'ACTIVE'\n")
				// lower(name), as KitchenOrder.settingsOrder() sorts (Epic 12): the planner's sections follow
				// "Settings order", so the Settings list and the planner must not disagree over two
				// kitchens whose names differ only in case.
				+ "ORDER BY k.is_main DESC, lower(k.name), k.id";
		return jdbc.query(sql, VIEW_MAPPER);
	}

	/**
	 * One kitchen, archived or not. An archived kitchen stays readable on purpose: it is what the
	 * restore screen shows, and what a request raised months ago still points at.
	 */
	@Transactional(readOnly = true)
	public KitchenView get(UUID id) {
		return find(id).orElseThrow(() -> notFound(id));
	}

	/**
	 * Records another kitchen.
	 *
	 * <p>The temple's first kitchen is made main whatever the caller asked for. A temple with one
	 * kitchen has no other kitchen for the flag to sit on, and one that is not the main one is a
	 * state nobody could explain to the person looking at it.
	 */
	@Transactional
	public UUID create(AuthenticatedUser actor, CreateKitchenRequest request) {
		resolveInCharge(request.inChargeUserId());

		boolean isFirst = countKitchens() == 0;
		boolean main = isFirst || request.isMain();
		if (main && !isFirst) {
			clearExistingMain(actor);
		}

		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO kitchens (id, tenant_id, name, description, location, is_main,
							uses_meal_planner, in_charge_user_id, contact_phone, status, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?,
							'ACTIVE', ?)
					""",
					id, request.name().trim(), trimToNull(request.description()),
					trimToNull(request.location()), main, request.usesMealPlanner(),
					request.inChargeUserId(), trimToNull(request.contactPhone()), actor.getUserId());
		} catch (DuplicateKeyException e) {
			throw onDuplicate(e, request.name().trim());
		}

		// A kitchen created with the meal planner already on has nothing in flight to settle — it
		// has existed for a microsecond and nobody has asked it for anything. The cascade belongs on
		// the edit, which is where a temple actually turns this on (E10-S4).

		auditService.record(actor, AuditAction.KITCHEN_CREATED, AuditEntityType.KITCHEN, id,
				null, snapshot(request.name().trim(), main, request.usesMealPlanner(), "ACTIVE"), null);
		return id;
	}

	/**
	 * Edits a kitchen. A full replacement of the editable fields, so clearing who runs it is
	 * expressible; an archived one is refused, because editing something a temple has closed is a
	 * change nobody will see the effect of.
	 *
	 * <p>Ticking main moves the flag off whichever kitchen holds it. Un-ticking it does nothing: a
	 * temple changes its main kitchen by naming the new one, and an edit that quietly leaves the
	 * temple with no main kitchen at all is not a door this register offers.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateKitchenRequest request) {
		KitchenView before = get(id);
		requireActive(before);
		resolveInCharge(request.inChargeUserId());

		boolean main = before.isMain() || request.isMain();
		if (main && !before.isMain()) {
			clearExistingMain(actor);
		}

		try {
			jdbc.update("""
					UPDATE kitchens
					SET name = ?, description = ?, location = ?, is_main = ?, uses_meal_planner = ?,
						in_charge_user_id = ?, contact_phone = ?, updated_at = now()
					WHERE id = ?
					""",
					request.name().trim(), trimToNull(request.description()),
					trimToNull(request.location()), main, request.usesMealPlanner(),
					request.inChargeUserId(), trimToNull(request.contactPhone()), id);
		} catch (DuplicateKeyException e) {
			throw onDuplicate(e, request.name().trim());
		}

		// Where the meal planner has just been turned on, every request already in flight for this
		// kitchen is settled inside this same transaction — drafts deleted, anything awaiting or
		// holding approval denied — before the caller is told the save succeeded. There is no
		// instant in which a kitchen both plans its meals and holds live requests, which is the
		// whole point: that instant is where the double-count would live (E10-S4).
		boolean joining = request.usesMealPlanner() && !before.usesMealPlanner();
		boolean leaving = !request.usesMealPlanner() && before.usesMealPlanner();

		if (joining) {
			MealPlannerAdoption.Impact settled =
					mealPlannerAdoption.settle(actor, id, request.name().trim());

			auditService.record(actor, AuditAction.KITCHEN_JOINED_MEAL_PLANNER,
					AuditEntityType.KITCHEN, id,
					Map.of("usesMealPlanner", false),
					Map.of("usesMealPlanner", true,
							"draftsDeleted", settled.draftsDeleted(),
							"requestsDenied", settled.requestsDenied()),
					null);
		} else if (leaving) {
			// The trivial direction. The kitchen may ask the store again from this moment, and
			// nothing already recorded changes — a denial stays denied, because it was answered.
			auditService.record(actor, AuditAction.KITCHEN_LEFT_MEAL_PLANNER,
					AuditEntityType.KITCHEN, id,
					Map.of("usesMealPlanner", true), Map.of("usesMealPlanner", false), null);
		}

		auditService.record(actor, AuditAction.KITCHEN_UPDATED, AuditEntityType.KITCHEN, id,
				snapshot(before.name(), before.isMain(), before.usesMealPlanner(), before.status()),
				snapshot(request.name().trim(), main, request.usesMealPlanner(), before.status()), null);
	}

	/**
	 * Closes a kitchen without removing it. This is what a kitchen that has asked for ingredients
	 * gets instead of deletion — the requests it was named on stay readable, and it stops being an
	 * option when somebody raises a new one.
	 */
	@Transactional
	public void archive(AuthenticatedUser actor, UUID id) {
		KitchenView before = get(id);
		if ("ARCHIVED".equals(before.status())) {
			return;
		}
		requireNoCurrentStaff(before);
		jdbc.update("UPDATE kitchens SET status = 'ARCHIVED', updated_at = now() WHERE id = ?", id);
		auditService.record(actor, AuditAction.KITCHEN_ARCHIVED, AuditEntityType.KITCHEN, id,
				Map.of("status", "ACTIVE"), Map.of("status", "ARCHIVED"), null);
	}

	/**
	 * Reopens an archived kitchen, so archiving is a decision and not a one-way door.
	 *
	 * <p>It comes back holding whatever {@code is_main} it had, which cannot collide: moving the
	 * flag to another kitchen clears it wherever it sits, archived or not, so an archived kitchen
	 * never keeps a claim on a title another one has since taken.
	 */
	@Transactional
	public void restore(AuthenticatedUser actor, UUID id) {
		KitchenView before = get(id);
		if ("ACTIVE".equals(before.status())) {
			return;
		}
		jdbc.update("UPDATE kitchens SET status = 'ACTIVE', updated_at = now() WHERE id = ?", id);
		auditService.record(actor, AuditAction.KITCHEN_RESTORED, AuditEntityType.KITCHEN, id,
				Map.of("status", "ARCHIVED"), Map.of("status", "ACTIVE"), null);
	}

	/**
	 * Removes a kitchen outright — but only one nothing has ever asked the store through.
	 *
	 * <p>Two different things get called delete. A kitchen somebody typed twice, or misspelled, or
	 * was trying the form out with, is rubbish and should leave without a trace. A kitchen named on
	 * requests is part of the record of who was fed what, and {@code ingredient_requests.kitchen_id}
	 * is {@code ON DELETE RESTRICT} so that record cannot be quietly hollowed out. The system
	 * decides which one this is rather than asking, and the refusal names the alternative.
	 */
	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		KitchenView before = get(id);

		// People working here now is the refusal with a way forward of its own — move them first —
		// so it is asked before the general "something points at it" question below.
		requireNoCurrentStaff(before);
		if (isReferenced(id)) {
			throw new ApplicationException(ErrorCode.KITCHEN_IN_USE, Map.of("kitchenId", id));
		}

		// Audited before the row goes, so the entry describes something that still exists to be
		// described. The after-state is deliberately null: there is no after.
		auditService.record(actor, AuditAction.KITCHEN_DELETED, AuditEntityType.KITCHEN, id,
				snapshot(before.name(), before.isMain(), before.usesMealPlanner(), before.status()),
				null, "Never asked the store for anything, so nothing references it.");

		jdbc.update("DELETE FROM kitchens WHERE id = ?", id);
	}

	// ---------------------------------------------------------------------

	/**
	 * Whether anything still points at this kitchen. Asked before the delete rather than after, so
	 * the person is told what is wrong instead of being handed whatever the database says when a
	 * RESTRICT check fails — which, on an append-only neighbour, has reached a user as an internal
	 * error before now.
	 */
	private boolean isReferenced(UUID id) {
		// Two more references since Epic 12, both ON DELETE RESTRICT, and both would otherwise reach
		// the user as an internal error at the DELETE: a staff record that names this kitchen (a former
		// employee's — current staff are refused before this with KMS-400185), and a meal it cooked.
		// Both are history the kitchen is part of, which is exactly the case archiving is for.
		//
		// The refusal is KMS-400107, whose sentence names only ingredient requests. It is the closest
		// code whose next step is right ("archive it instead"); a sentence that covers all three is a
		// wording change to ErrorCode.java, which T-357 does not own (flagged in its proof).
		Integer references = jdbc.queryForObject("""
				SELECT (SELECT count(*) FROM ingredient_requests WHERE kitchen_id = ?)
					 + (SELECT count(*) FROM staff_profiles WHERE kitchen_id = ?)
					 + (SELECT count(*) FROM meal_kitchens WHERE kitchen_id = ?)
				""", Integer.class, id, id, id);
		return references != null && references > 0;
	}

	/**
	 * Refuses to close a kitchen people are working in now (Epic 12, {@code KMS-400185}).
	 *
	 * <p>Every staff member belongs to exactly one kitchen, and an archived kitchen is one nobody should
	 * be put to work in — the staff form refuses it. Archiving a kitchen out from under its staff would
	 * leave them in exactly that state, with no planner if it was their route to one, and nothing on
	 * any screen saying why. So the temple moves them first, on the Staff page, and then closes it.
	 *
	 * <p>Only current staff count. A former employee's record still names the kitchen they left from,
	 * and that must not stop a kitchen being archived for ever.
	 */
	private void requireNoCurrentStaff(KitchenView kitchen) {
		if (kitchen.staffCount() > 0) {
			throw new ApplicationException(ErrorCode.KITCHEN_HAS_STAFF,
					Map.of("kitchenId", kitchen.id(), "staffCount", kitchen.staffCount()));
		}
	}

	/**
	 * Takes the main flag off whichever kitchen holds it, and audits the kitchen that lost it.
	 *
	 * <p>Archived kitchens are included deliberately: a temple that archived its main kitchen still
	 * has the flag sitting on that row, and the new main kitchen has to be able to take it.
	 */
	private void clearExistingMain(AuthenticatedUser actor) {
		List<UUID> previous = jdbc.queryForList(
				"SELECT id FROM kitchens WHERE is_main", UUID.class);
		if (previous.isEmpty()) {
			return;
		}
		jdbc.update("UPDATE kitchens SET is_main = false, updated_at = now() WHERE is_main");
		for (UUID id : previous) {
			auditService.record(actor, AuditAction.KITCHEN_UPDATED, AuditEntityType.KITCHEN, id,
					Map.of("isMain", true), Map.of("isMain", false),
					"Another kitchen was made the temple's main kitchen.");
		}
	}

	/** How many kitchens the temple has, archived ones included — RLS scopes it to the tenant. */
	private int countKitchens() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM kitchens", Integer.class);
		return count == null ? 0 : count;
	}

	/**
	 * Confirms the named person has an account at this temple, so an id belonging to another temple's
	 * user finds nothing and is refused as unknown rather than being accepted by a foreign key that
	 * does not know about tenants.
	 *
	 * <p>The temple is named in the query and not left to RLS (T-190). The id comes from the request
	 * body, and the read policy on {@code users} also shows the signed-in caller their own accounts at
	 * other temples ({@code firebase_uid = app.auth_uid}, V2). Trusting RLS alone, an administrator
	 * could put this temple's kitchen in the charge of their own account at another temple — which the
	 * user register, before its own fix, even offered them to pick.
	 */
	private void resolveInCharge(UUID userId) {
		if (userId == null) {
			return;
		}
		Integer found = jdbc.queryForObject("""
				SELECT count(*) FROM users
				WHERE id = ? AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", Integer.class, userId);
		if (found == null || found == 0) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "inChargeUserId", "value", userId));
		}
	}

	private void requireActive(KitchenView kitchen) {
		if ("ARCHIVED".equals(kitchen.status())) {
			throw new ApplicationException(ErrorCode.KITCHEN_ARCHIVED, Map.of("kitchenId", kitchen.id()));
		}
	}

	/**
	 * Which unique index the write collided with.
	 *
	 * <p>The name index is an ordinary mistake and gets an ordinary message.
	 *
	 * <p>The main-kitchen index is not reachable by any single caller — this service clears the flag
	 * before it sets it, in the same transaction — so a collision there means two administrators
	 * moved it in the same instant. That is not a defect and it is not the caller's fault, so it
	 * does not get an incident id: it gets a sentence saying what happened and what to do about it.
	 * The database has still done its job either way, and neither temple ends up with two mains.
	 */
	/**
	 * What turning the meal planner on for this kitchen would settle, without settling it.
	 *
	 * <p>The screen asks before it saves, so that ticking a checkbox cannot silently delete somebody
	 * else's drafts. See {@link MealPlannerAdoption}.
	 */
	@Transactional(readOnly = true)
	public MealPlannerAdoption.Impact mealPlannerImpact(UUID id) {
		get(id);
		return mealPlannerAdoption.preview(id);
	}

	private RuntimeException onDuplicate(DuplicateKeyException e, String name) {
		String cause = String.valueOf(e.getMostSpecificCause().getMessage());
		if (cause.contains("kitchens_name_per_tenant")) {
			return new ApplicationException(ErrorCode.KITCHEN_NAME_TAKEN, Map.of("name", name), e);
		}
		if (cause.contains("kitchens_one_main_per_tenant")) {
			return new ApplicationException(ErrorCode.KITCHEN_MAIN_MOVED, Map.of("name", name), e);
		}
		return e;
	}

	private Optional<KitchenView> find(UUID id) {
		return jdbc.query("""
				SELECT k.id, k.name, k.description, k.location, k.is_main, k.uses_meal_planner,
					   k.in_charge_user_id, u.full_name AS in_charge_name, k.contact_phone,
					   k.status, k.created_at,
					   (SELECT count(*) FROM staff_profiles sp
						WHERE sp.kitchen_id = k.id AND sp.employment_status = 'ACTIVE') AS staff_count
				FROM kitchens k
				LEFT JOIN users u ON u.id = k.in_charge_user_id
				WHERE k.id = ?
				""", VIEW_MAPPER, id).stream().findFirst();
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.KITCHEN_NOT_FOUND, Map.of("kitchenId", id));
	}

	private static Map<String, Object> snapshot(
			String name, boolean isMain, boolean usesMealPlanner, String status) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", name);
		snapshot.put("isMain", isMain);
		snapshot.put("usesMealPlanner", usesMealPlanner);
		snapshot.put("status", status);
		return snapshot;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static final RowMapper<KitchenView> VIEW_MAPPER = (rs, rowNum) -> new KitchenView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getString("description"),
			rs.getString("location"),
			rs.getBoolean("is_main"),
			rs.getBoolean("uses_meal_planner"),
			rs.getObject("in_charge_user_id", UUID.class),
			rs.getString("in_charge_name"),
			rs.getInt("staff_count"),
			rs.getString("contact_phone"),
			rs.getString("status"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
