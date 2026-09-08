package org.iskcon.kms.meal;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The kinds of meal a temple cooks (E4-S7), and when each is due.
 *
 * <p>Seeded on provisioning: Breakfast, Lunch and Dinner with the temple's usual times, and Festival
 * feast, Deity Offering and Event with none. That absence is the design, not an omission — an
 * everyday meal has a known hour, an occasional one does not, and a guessed time for an event is
 * worse than being asked for one.
 *
 * <p>Flags say what a kind needs beyond a recipe: {@code isEvent} for an occasion with a name of its
 * own that may be going outside the temple (E4-S15), and {@code needsOccasion} for a feast, which
 * must name the festival it is for (item 26). They are flags rather than known names so a temple can
 * add kinds of its own — a *Catering event*, say — without the application having to recognise them.
 *
 * <p>A feast being a kind rather than a day type is the point of it. A kind says when in the day a
 * meal happens and what it needs; a day type says what sort of day it is, derived and never chosen.
 * On Janmashtami the temple serves an ordinary breakfast and then a feast — one day, two meals, one
 * of them the big one — and only a per-meal fact can say which.
 */
@Service
public class MealKindService {

	private final JdbcTemplate jdbc;

	public MealKindService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<MealKindView> list() {
		return jdbc.query(SELECT + " ORDER BY sort_order, name", MAPPER);
	}

	/** One kind by name, as a meal plan refers to it. Empty when the temple has no such kind. */
	@Transactional(readOnly = true)
	public Optional<MealKindView> byName(String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return jdbc.query(SELECT + " WHERE lower(name) = lower(?)", MAPPER, name.trim())
				.stream().findFirst();
	}

	/** The kind a plan names, or a refusal naming what the temple actually has. */
	@Transactional(readOnly = true)
	public MealKindView require(String name) {
		return byName(name).orElseThrow(() -> new ApplicationException(
				ErrorCode.MEAL_KIND_UNKNOWN,
				Map.of("mealKind", String.valueOf(name), "known", list().stream().map(MealKindView::name).toList())));
	}

	@Transactional
	public UUID create(CreateMealKindRequest request) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO meal_kinds (
						id, tenant_id, name, sort_order, default_ready_time, is_event, needs_occasion)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""", id, request.name().trim(), request.sortOrder(), request.defaultReadyTime(),
					request.isEvent(), request.needsOccasion());
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.MEAL_KIND_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		return id;
	}

	/**
	 * Changes a kind's name, order and — the point of the screen — the time its meals are due. A
	 * default of null is meaningful: it makes the kind always ask.
	 *
	 * <p>A rename carries the new name to everything already recorded under the old one. That is not
	 * a nicety: a meal does not <em>reference</em> its kind, it <em>stores the name</em>, in three
	 * places — {@code meal_plans.meal_kind}, {@code meal_services.meal_kind} (V64) and
	 * {@code shifts.meal_kind} (V95). No foreign key is available to do it for us, because
	 * {@code meal_kinds} is unique on an EXPRESSION index over {@code (tenant_id, lower(name))} and
	 * PostgreSQL will not accept an expression index as the target of a foreign key. So the cascade
	 * is written by hand here, and if it were not, a rename would orphan every plan, every recorded
	 * meal and every linked shift the temple has — silently, and only visible later when a job card
	 * or a reuse preview tried to resolve a kind that no longer exists.
	 *
	 * <p>Renaming a kind onto a name the temple already has is a typo, and it is answered the way
	 * {@link #create} answers the same typo — {@code MEAL_KIND_ALREADY_EXISTS}, caught off the
	 * database rather than pre-checked, so two admins renaming at once cannot slip between a SELECT
	 * and an UPDATE. It is <strong>case-insensitive</strong> because the index is:
	 * {@code meal_kinds_name_per_tenant} is over {@code (tenant_id, lower(name))} (V22 as
	 * {@code meal_slots_name_per_tenant}, renamed by V48), so renaming "Lunch" to "DINNER" collides
	 * with the temple's "Dinner". Renaming a kind to what it is already called is not a collision at
	 * all, in any case: the row's own index entry is the one being rewritten, and rewriting it to
	 * the value it already holds conflicts with nothing.
	 */
	@Transactional
	public void update(UUID id, CreateMealKindRequest request) {
		String newName = request.name().trim();
		// Read before write: after the UPDATE the old name is gone, and it is the only thing the
		// three tables can be found by. Scoped by RLS like everything else — an id belonging to
		// another temple reads as absent, which is the refusal below.
		String oldName = jdbc.query("SELECT name FROM meal_kinds WHERE id = ?",
				rs -> rs.next() ? rs.getString(1) : null, id);

		int rows;
		try {
			// Only this statement is inside the try. The cascade below must not be: meal_services is
			// unique on (tenant_id, plan_date, meal_kind, ...) and could in principle raise a
			// DuplicateKeyException of its own, which is a different fault entirely and must not be
			// reported to the temple as "that kind of meal already exists".
			rows = jdbc.update("""
					UPDATE meal_kinds
					SET name = ?, sort_order = ?, default_ready_time = ?, is_event = ?, needs_occasion = ?
					WHERE id = ?
					""", newName, request.sortOrder(), request.defaultReadyTime(),
					request.isEvent(), request.needsOccasion(), id);
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.MEAL_KIND_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		if (rows == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealKindId", id));
		}

		// Only when the name actually moved. Exact equality on purpose: changing "lunch" to "Lunch"
		// IS a change — it is what every screen prints, and the stored copies have to print it too —
		// so a case-only edit cascades rather than being dismissed as a no-op. The common edit is a
		// ready-by time, and that must not walk three tables for nothing.
		if (oldName != null && !oldName.equals(newName)) {
			renameEverythingRecordedAs(oldName, newName);
		}
	}

	/**
	 * Carries a renamed kind to the three tables that store it by name.
	 *
	 * <p><strong>Matched case-insensitively</strong>, and that was decided on the evidence rather
	 * than by symmetry with the uniqueness index. Two of the three columns are written from
	 * {@code require(...).name()} and so hold the canonical spelling, where an exact match would have
	 * done. The third does not: {@link org.iskcon.kms.shift.ShiftService} stores
	 * {@code shifts.meal_kind} as the caller typed it — {@code trimToNull(request.mealKind())}, never
	 * routed through this service — and it is read back through
	 * {@link org.iskcon.kms.staff.MealMoment}, which folds both sides to lower case precisely so that
	 * a shift posted for "lunch" counts toward the temple's "Lunch". A shift linked to "lunch" is
	 * therefore a real, supported row, and an exact match would rename the meals around it and leave
	 * it pointing at a kind that no longer exists. Folding matches everything an exact comparison
	 * would match and nothing it would not, which is the same argument MealMoment makes for folding
	 * the kind at all.
	 *
	 * <p><strong>No blanket UPDATE.</strong> There is no {@code tenant_id} in these statements
	 * because there must not be one: all three tables carry FORCE ROW LEVEL SECURITY, the connection
	 * is scoped to the request's tenant, and the policy is what confines the rewrite to this temple.
	 * Another temple with a kind of the same name is untouched, and {@code MealKindIT} proves that
	 * against a real database with a second tenant present rather than asserting it here.
	 *
	 * <p><strong>Not append-only</strong>, checked rather than assumed. {@code make_append_only()}
	 * (V49, V50) refuses an UPDATE with a BEFORE UPDATE OR DELETE trigger rather than with a
	 * constraint, which is nothing a reader of this method would see. The registered tables are
	 * audit_events, platform_audit_events, stock_movements, equipment_state_changes, po_events,
	 * goods_receipts, goods_receipt_lines, invoice_payments, shift_broadcasts, staff_conduct_notes,
	 * equipment_services and vendor_status_changes. None of these three is among them, so the
	 * rewrite is legitimate — and if one of them ever joins that list, this method stops working and
	 * the design has to be revisited rather than worked around.
	 */
	private void renameEverythingRecordedAs(String oldName, String newName) {
		// The planned dishes. A meal is a group of these rows sharing a date and a kind, so this is
		// the one that keeps the planner, the menu history and the sufficiency read whole.
		jdbc.update("""
				UPDATE meal_plans SET meal_kind = ?, updated_at = now()
				WHERE lower(meal_kind) = lower(?)
				""", newName, oldName);

		// The recorded meal and its job card. Its unique index is on the exact meal_kind, so in
		// principle a case-insensitive rewrite could collide — but only if two rows for one date and
		// event already differed by case, which is a duplicated meal the application cannot produce:
		// every write here goes through require(...).name().
		jdbc.update("""
				UPDATE meal_services SET meal_kind = ?, updated_at = now()
				WHERE lower(meal_kind) = lower(?)
				""", newName, oldName);

		// The volunteer shifts posted for a meal of this kind (D-14). The case a naive cascade
		// forgets, because a shift is not a meal and lives in another package entirely.
		jdbc.update("""
				UPDATE shifts SET meal_kind = ?, updated_at = now()
				WHERE lower(meal_kind) = lower(?)
				""", newName, oldName);
	}

	/**
	 * Removes a kind the temple has never used, and refuses to remove one it has.
	 *
	 * <p>This used to be unconditional, on the stated grounds that "a plan records its kind by name,
	 * not by reference, so removing a kind never breaks the meals already planned under it — they
	 * keep reading as what they were." That is true only if the stored name is a snapshot, and it is
	 * not: four read-shaped paths take the stored historical name and resolve it back through
	 * {@link #require} —
	 *
	 * <ul>
	 *   <li>{@code MealPlanService.previewReuse}, reached by {@code POST /api/v1/meal-plans/reuse/preview}
	 *       (a POST only because it carries a body; the controller says it is read-only). It walks
	 *       every historical plan in the window and resolves each distinct stored kind, so one
	 *       deleted kind anywhere in that window throws the whole preview away.
	 *   <li>{@code ServedMealService.find}, reached from {@code GET /api/v1/job-cards/languages} and
	 *       from {@code JobCardService.build()} rendering a meal that was already served.
	 *   <li>{@code ServedMealService.issueCardNumber} and {@code serviceFor}, reached from
	 *       {@code GET /api/v1/job-cards/print} and {@code GET /api/v1/job-cards/documents} as well
	 *       as from {@code POST /api/v1/job-cards}.
	 * </ul>
	 *
	 * <p>So deleting a kind that has ever been used arms a failure in history nobody touched, and it
	 * goes off far from the settings screen that caused it — on a job card, or on a reuse preview,
	 * as KMS-400071. Refusing here is the smaller of the two honest answers. The larger one is to
	 * deactivate rather than delete, which needs a column and a migration and answers a question
	 * nobody has asked yet ("may a temple retire a kind it has used for a year?"); refusing forecloses
	 * none of it, because deactivation is a strict superset of this. What refusing buys today is that
	 * the delete button stops shipping a time bomb, while the common real case — a kind added by
	 * mistake, used for nothing — still works.
	 *
	 * <p>The refusal points at the rename, which is the thing the temple almost always meant.
	 */
	@Transactional
	public void delete(UUID id) {
		// Deleting a kind that is not there stays a silent no-op, as it has always been: the screen's
		// delete is idempotent and a second click must not raise.
		String name = jdbc.query("SELECT name FROM meal_kinds WHERE id = ?",
				rs -> rs.next() ? rs.getString(1) : null, id);
		if (name == null) {
			return;
		}

		// All three, and shifts is the one worth naming: a kind used by nothing but a linked shift is
		// exactly the case a check written from the planner's point of view would miss, and the shift
		// would be left pointing at a kind that no longer exists. Folded on both sides for the same
		// reason the rename folds — shifts.meal_kind holds what the caller typed.
		Usage usage = jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM meal_plans    WHERE lower(meal_kind) = lower(?)) AS in_plans,
				       EXISTS (SELECT 1 FROM meal_services WHERE lower(meal_kind) = lower(?)) AS in_services,
				       EXISTS (SELECT 1 FROM shifts        WHERE lower(meal_kind) = lower(?)) AS in_shifts
				""",
				(rs, n) -> new Usage(
						rs.getBoolean("in_plans"), rs.getBoolean("in_services"), rs.getBoolean("in_shifts")),
				name, name, name);

		if (usage.anywhere()) {
			throw new ApplicationException(ErrorCode.MEAL_KIND_IN_USE, Map.of(
					"mealKindId", id,
					"name", name,
					"plannedMeals", usage.inPlans(),
					"recordedMeals", usage.inServices(),
					"linkedShifts", usage.inShifts()));
		}

		jdbc.update("DELETE FROM meal_kinds WHERE id = ?", id);
	}

	/** Where a kind's name still appears, per table, so the refusal can say which. */
	private record Usage(boolean inPlans, boolean inServices, boolean inShifts) {
		boolean anywhere() {
			return inPlans || inServices || inShifts;
		}
	}

	@Transactional
	public void seedForCurrentTenant() {
		// Six kinds, and the last of them is Event (E4-S15). It absorbed *Outside event* and
		// *Catering order*, which are gone: a temple that does catering plans a catering event, and
		// gains six fields by it. A freshly provisioned temple must never see either of the old names
		// again, which is what V88's fold does for the temples that already exist — one for each half
		// of the estate, and between them the whole change.
		//
		// Festival feast sits at 35 so the picker reads: the three everyday meals, the feast, then the
		// kinds that are not a sitting at all. The occasional kinds carry no ready time — a feast is
		// never at the same hour twice, and neither is an event, so both always ask (item 26, V48).
		Object[][] defaults = {
			{"Breakfast", 10, LocalTime.of(7, 30), false, false},
			{"Lunch", 20, LocalTime.of(12, 0), false, false},
			{"Dinner", 30, LocalTime.of(19, 30), false, false},
			{"Festival feast", 35, null, false, true},
			{"Deity Offering", 40, null, false, false},
			{"Event", 50, null, true, false},
		};
		for (Object[] k : defaults) {
			jdbc.update("""
					INSERT INTO meal_kinds (
						tenant_id, name, sort_order, default_ready_time, is_event, needs_occasion)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					ON CONFLICT (tenant_id, lower(name)) DO NOTHING
					""", k[0], k[1], k[2], k[3], k[4]);
		}
	}

	private static final String SELECT = """
			SELECT id, name, sort_order, default_ready_time, is_event, needs_occasion
			FROM meal_kinds""";

	private static final RowMapper<MealKindView> MAPPER = (rs, n) -> new MealKindView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getInt("sort_order"),
			rs.getObject("default_ready_time", LocalTime.class),
			rs.getBoolean("is_event"),
			rs.getBoolean("needs_occasion"));
}
