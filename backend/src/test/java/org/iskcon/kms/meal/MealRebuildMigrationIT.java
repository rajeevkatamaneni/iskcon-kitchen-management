package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V135, V136 and V137 run against temples that have meals, stock and volunteer shifts in them
 * (D-27, T-195).
 *
 * <p><b>Why this exists.</b> Rajeev ruled that a meal becomes a row of its own and that the meal data
 * is wiped and reseeded rather than converted. The wipe is the dangerous half, and it is dangerous
 * <em>quietly</em>, in three ways a green suite on an empty database would never show:
 *
 * <ul>
 *   <li><b>Stock.</b> On hand is a sum over the ledger (V116). Deleting a cooked meal's draws puts
 *       the food back on the shelf in the books. V135 writes balancing adjustments so that nothing
 *       moves, and the only proof that nothing moved is to read every figure before and after, per
 *       ingredient and per batch, on a temple that actually drew stock, corrected a draw, and cooked
 *       beyond what the books held.
 *   <li><b>Row-level security.</b> The migration role is not a superuser, so a DELETE without the
 *       temple adopted matches nothing and reports success. The migration "works" and the rows are all
 *       still there. So the rows are counted afterwards, not the migration's exit status.
 *   <li><b>The per-temple loop.</b> A loop that adopted only the first temple would reset one and
 *       leave the next, and a balancing adjustment written under the wrong temple would lift one
 *       temple's stock and sink another's. Both pass with one temple. So there are three: two with
 *       meals whose draws differ in size, so a leak shows as a wrong figure, and one with no recipes,
 *       which the reseed must leave alone.
 * </ul>
 *
 * <p>Following {@link CateringMigrationIT}: a throwaway database, migrated as the unprivileged
 * migration role to the last version before the rebuild, seeded in the shapes the rows really had,
 * then migrated one version at a time with a snapshot taken between each. The fixture is written
 * through the superuser because what is under test is the migrations, not the seed. The new rules are
 * then exercised as the application role, because a superuser bypasses row-level security and a test
 * run as one proves nothing about it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MealRebuildMigrationIT extends AbstractIntegrationTest {

	/** The last schema with meal_plans, meal_services and the text-keyed shift link. */
	private static final String BEFORE_THE_REBUILD = "134";
	private static final String AFTER_THE_RESET = "135";
	private static final String AFTER_THE_SCHEMA = "136";

	private static final String DATABASE = "kms_meal_rebuild_check";

	private static final String VOLUNTEER_EMAIL = "ikms.volunteer.1@trading4good.org";

	/** On hand per temple, ingredient and batch, in the family's base unit, at each point. */
	private Map<String, BigDecimal> onHandBefore;
	private Map<String, BigDecimal> onHandAfterReset;
	private Map<String, BigDecimal> onHandAfterReseed;

	/** What must be gone, and what must be kept, counted straight after V135. */
	private final Map<String, Long> keptBefore = new LinkedHashMap<>();
	private final Map<String, Long> afterReset = new LinkedHashMap<>();
	private final Map<String, Long> keptAfterReset = new LinkedHashMap<>();

	private long movementsAfterReset;
	private long movementsAfterReseed;

	/** The reset's adjustments, read straight after V135: temple|ingredient -> summed base quantity. */
	private Map<String, BigDecimal> adjustmentsByIngredient;
	private List<String> adjustmentLabels;

	// The queries below are the definition of "gone" and "kept", written once so the before and after
	// counts cannot drift apart.
	private static final Map<String, String> MUST_BE_GONE = new LinkedHashMap<>();
	private static final Map<String, String> MUST_BE_KEPT = new LinkedHashMap<>();

	static {
		MUST_BE_GONE.put("dishes", "SELECT count(*) FROM meal_plans");
		MUST_BE_GONE.put("meal services", "SELECT count(*) FROM meal_services");
		MUST_BE_GONE.put("shifts", "SELECT count(*) FROM shifts");
		MUST_BE_GONE.put("signups", "SELECT count(*) FROM shift_signups");
		MUST_BE_GONE.put("waitlist", "SELECT count(*) FROM shift_waitlist");
		MUST_BE_GONE.put("reminders", "SELECT count(*) FROM shift_reminders");
		MUST_BE_GONE.put("broadcasts", "SELECT count(*) FROM shift_broadcasts");
		MUST_BE_GONE.put("broadcast recipients", "SELECT count(*) FROM shift_broadcast_recipients");
		MUST_BE_GONE.put("job card PDFs", "SELECT count(*) FROM documents WHERE kind = 'JOB_CARD_PDF'");
		MUST_BE_GONE.put("card counters", "SELECT count(*) FROM meal_card_sequence");
		MUST_BE_GONE.put("meal draws", "SELECT count(*) FROM stock_movements WHERE reference_type = 'MEAL_PLAN'");
		// A correction of a meal draw, or a correction left pointing at a movement that no longer exists —
		// the second is what a reset that deleted the draw and forgot its correction would leave behind.
		MUST_BE_GONE.put("corrections of meal draws", """
				SELECT count(*) FROM stock_movements c
				LEFT JOIN stock_movements o ON o.id = c.reference_id
				WHERE c.reference_type = 'CORRECTION'
				  AND (o.id IS NULL OR o.reference_type = 'MEAL_PLAN')
				""");
		MUST_BE_GONE.put("reminder jobs", "SELECT count(*) FROM qrtz_job_details WHERE job_group LIKE 'shiftrem-%'");
		MUST_BE_GONE.put("reminder triggers", "SELECT count(*) FROM qrtz_triggers WHERE job_group LIKE 'shiftrem-%'");
		MUST_BE_GONE.put("job card render jobs", "SELECT count(*) FROM qrtz_job_details WHERE job_name LIKE 'generate-document-%'");

		MUST_BE_KEPT.put("recipe PDFs", "SELECT count(*) FROM documents WHERE kind = 'RECIPE_PDF'");
		MUST_BE_KEPT.put("vendors", "SELECT count(*) FROM vendors");
		MUST_BE_KEPT.put("purchase orders", "SELECT count(*) FROM purchase_orders");
		MUST_BE_KEPT.put("deliveries", "SELECT count(*) FROM goods_receipts");
		MUST_BE_KEPT.put("delivery lines", "SELECT count(*) FROM goods_receipt_lines");
		MUST_BE_KEPT.put("delivery movements", "SELECT count(*) FROM stock_movements WHERE movement_type = 'PO_RECEIPT'");
		MUST_BE_KEPT.put("donations in kind", "SELECT count(*) FROM stock_movements WHERE movement_type = 'DONATION_IN_KIND'");
		MUST_BE_KEPT.put("a spoilage adjustment and its correction", """
				SELECT count(*) FROM stock_movements
				WHERE reason_category = 'SPOILAGE'
				   OR (reference_type = 'CORRECTION' AND reason_category = 'COUNT_CORRECTION'
				       AND reference_id IN (SELECT id FROM stock_movements WHERE reason_category = 'SPOILAGE'))
				""");
		MUST_BE_KEPT.put("audit history", "SELECT count(*) FROM audit_events");
		MUST_BE_KEPT.put("notifications", "SELECT count(*) FROM notifications");
		MUST_BE_KEPT.put("recipes", "SELECT count(*) FROM recipes");
		MUST_BE_KEPT.put("ingredients", "SELECT count(*) FROM ingredients");
		MUST_BE_KEPT.put("users", "SELECT count(*) FROM users");
		MUST_BE_KEPT.put("meal kinds", "SELECT count(*) FROM meal_kinds");
		MUST_BE_KEPT.put("the send job of a notification", "SELECT count(*) FROM qrtz_job_details WHERE job_name LIKE 'send-%'");
		MUST_BE_KEPT.put("a global job", "SELECT count(*) FROM qrtz_job_details WHERE job_name = 'global-sweep'");
	}

	@BeforeAll
	void runTheRebuildOverTemplesThatHaveMeals() throws SQLException {
		recreateDatabase();
		migrateTo(BEFORE_THE_REBUILD);

		seedTemples();
		onHandBefore = onHand();
		for (var e : MUST_BE_KEPT.entrySet()) {
			keptBefore.put(e.getKey(), countOf(e.getValue()));
		}
		// The fixture has to hold what the reset is meant to remove, or "all gone" is vacuous.
		for (var e : MUST_BE_GONE.entrySet()) {
			assertThat(countOf(e.getValue())).as("fixture must seed some %s", e.getKey()).isPositive();
		}

		migrateTo(AFTER_THE_RESET);
		onHandAfterReset = onHand();
		for (var e : MUST_BE_GONE.entrySet()) {
			afterReset.put(e.getKey(), countOf(e.getValue()));
		}
		for (var e : MUST_BE_KEPT.entrySet()) {
			keptAfterReset.put(e.getKey(), countOf(e.getValue()));
		}
		movementsAfterReset = countOf("SELECT count(*) FROM stock_movements");
		adjustmentsByIngredient = decimals("""
				SELECT t.slug || '|' || i.name, SUM(to_on_hand_qty(m.quantity, m.unit, m.movement_type))
				FROM stock_movements m
				JOIN ingredients i ON i.id = m.ingredient_id AND i.tenant_id = m.tenant_id
				JOIN tenants t ON t.id = m.tenant_id
				WHERE m.note LIKE 'Meal data reset%'
				GROUP BY 1
				""");
		adjustmentLabels = strings("""
				SELECT DISTINCT movement_type || '|' || reason_category || '|'
					|| COALESCE(reference_type, 'no reference') || '|' || COALESCE(reference_id::text, 'no id')
				FROM stock_movements WHERE note LIKE 'Meal data reset%'
				""");

		migrateTo(AFTER_THE_SCHEMA);
		migrateToTheEnd();
		onHandAfterReseed = onHand();
		movementsAfterReseed = countOf("SELECT count(*) FROM stock_movements");
	}

	// ---- V135, the reset --------------------------------------------------------------------------

	@Test
	@DisplayName("every on-hand figure, per ingredient and per batch, reads the same after the reset and the reseed")
	void onHandIsUnchanged() {
		assertThat(onHandBefore).as("the fixture has stock to compare").hasSizeGreaterThanOrEqualTo(9);

		assertThat(onHandAfterReset.keySet()).isEqualTo(onHandBefore.keySet());
		onHandBefore.forEach((key, before) -> assertThat(onHandAfterReset.get(key))
				.as("on hand for %s after V135", key)
				.isEqualByComparingTo(before));

		assertThat(onHandAfterReseed.keySet()).isEqualTo(onHandBefore.keySet());
		onHandBefore.forEach((key, before) -> assertThat(onHandAfterReseed.get(key))
				.as("on hand for %s after V137", key)
				.isEqualByComparingTo(before));
	}

	@Test
	@DisplayName("every meal, job card, shift and scheduled reminder is gone, in every temple")
	void mealAndShiftRowsAreGone() {
		afterReset.forEach((what, count) -> assertThat(count).as("%s left after the reset", what).isZero());
	}

	@Test
	@DisplayName("deliveries, purchase orders, audit history, recipes, people and settings are untouched")
	void everythingElseIsKept() {
		keptBefore.forEach((what, before) -> {
			assertThat(before).as("fixture must seed some %s", what).isPositive();
			assertThat(keptAfterReset.get(what)).as("%s after the reset", what).isEqualTo(before);
		});
	}

	@Test
	@DisplayName("the balancing adjustments are labelled, and each stays in its own temple at its own size")
	void adjustmentsAreLabelledAndPerTemple() {
		// Worked out from the fixture by hand, not from the migration's own query. Rice: -2 kg drawn,
		// corrected back +2 kg, re-drawn -1.5 kg in one batch, and -500 g from another. Ghee: -1.25 L.
		// Dal: only a used-beyond-recorded-stock row, which moves no stock and so needs no balance.
		// Temple B draws twice what temple A does, so a leak between them is a wrong number.
		assertThat(adjustmentsByIngredient).containsOnlyKeys(
				"rebuild-a|Rice", "rebuild-a|Ghee", "rebuild-b|Rice", "rebuild-b|Ghee");
		assertThat(adjustmentsByIngredient.get("rebuild-a|Rice")).isEqualByComparingTo("-2000");
		assertThat(adjustmentsByIngredient.get("rebuild-a|Ghee")).isEqualByComparingTo("-1250");
		assertThat(adjustmentsByIngredient.get("rebuild-b|Rice")).isEqualByComparingTo("-4000");
		assertThat(adjustmentsByIngredient.get("rebuild-b|Ghee")).isEqualByComparingTo("-2500");

		assertThat(adjustmentLabels)
				.as("an adjustment, filed as a count correction, pointing at nothing that no longer exists")
				.containsExactly("ADJUSTMENT|COUNT_CORRECTION|no reference|no id");
	}

	// ---- V136, the rules --------------------------------------------------------------------------

	@Test
	@DisplayName("a second meal with the same day, kind and event name differing only in case is refused")
	void aMealIsUniquePerDayKindAndEventName() throws SQLException {
		String temple = idOf("rebuild-a");
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			adopt(app, temple);

			String event = oneString(app, """
					SELECT m.meal_plan_day_id || '|' || m.meal_kind_id FROM meals m
					WHERE m.event_name = 'Children''s Bhagavad-gita Reading'
					""");
			String[] eventKey = event.split("\\|");
			assertThatThrownBy(() -> insertMeal(app, temple, eventKey[0], eventKey[1],
					"CHILDREN'S BHAGAVAD-GITA READING"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("meals_one_per_meal");
			app.rollback();
			adopt(app, temple);

			String lunch = oneString(app, """
					SELECT m.meal_plan_day_id || '|' || m.meal_kind_id FROM meals m
					JOIN meal_kinds mk ON mk.id = m.meal_kind_id
					WHERE lower(mk.name) = 'lunch' AND m.event_name IS NULL LIMIT 1
					""");
			String[] lunchKey = lunch.split("\\|");
			assertThatThrownBy(() -> insertMeal(app, temple, lunchKey[0], lunchKey[1], null))
					.as("two unnamed Lunches on one day are two answers to what went out at lunch")
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("meals_one_per_meal");
			app.rollback();
			adopt(app, temple);

			// And a different name on the same day and kind is a different meal.
			insertMeal(app, temple, eventKey[0], eventKey[1], "Bhajan Prasadam");
			app.rollback();
		}
	}

	@Test
	@DisplayName("a meal has one shift that is not cancelled, and a cancelled one does not block another")
	void oneLiveShiftPerMeal() throws SQLException {
		String temple = idOf("rebuild-a");
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			adopt(app, temple);
			String meal = oneString(app, "SELECT meal_id::text FROM shifts WHERE meal_id IS NOT NULL");
			String admin = oneString(app, "SELECT id::text FROM users WHERE role = 'TEMPLE_ADMIN'");

			assertThatThrownBy(() -> insertShift(app, temple, meal, admin, "OPEN"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("shifts_one_per_meal");
			app.rollback();
			adopt(app, temple);

			// A cancelled shift for the same meal sits beside the live one.
			insertShift(app, temple, meal, admin, "CANCELLED");

			// And once the live one is cancelled, the meal may ask again.
			try (PreparedStatement ps = app.prepareStatement(
					"UPDATE shifts SET status = 'CANCELLED' WHERE meal_id = ?::uuid AND status = 'OPEN'")) {
				ps.setString(1, meal);
				assertThat(ps.executeUpdate()).isEqualTo(1);
			}
			insertShift(app, temple, meal, admin, "OPEN");
			app.rollback();
		}
	}

	@Test
	@DisplayName("another temple sees nothing in meal_plan_days, meals or meal_dishes, and cannot write into them")
	void theNewTablesAreIsolated() throws SQLException {
		String a = idOf("rebuild-a");
		String c = idOf("rebuild-bare");
		List<String> tables = List.of("meal_plan_days", "meals", "meal_dishes");
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);

			adopt(app, a);
			for (String table : tables) {
				assertThat(count(app, "SELECT count(*) FROM " + table))
						.as("temple A reads its own %s", table).isPositive();
			}
			app.rollback();

			adopt(app, c);
			for (String table : tables) {
				assertThat(count(app, "SELECT count(*) FROM " + table))
						.as("temple C reading %s", table).isZero();
			}
			assertThatThrownBy(() -> {
				try (PreparedStatement ps = app.prepareStatement("""
						INSERT INTO meal_plan_days (tenant_id, plan_date, day_type)
						VALUES (?::uuid, DATE '2030-01-01', 'REGULAR')
						""")) {
					ps.setString(1, a);
					ps.executeUpdate();
				}
			}).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
			app.rollback();

			// And with no temple at all, nothing.
			for (String table : tables) {
				assertThat(count(app, "SELECT count(*) FROM " + table)).as("no temple reading %s", table).isZero();
			}
			app.rollback();
		}
	}

	// ---- V137, the reseed -------------------------------------------------------------------------

	@Test
	@DisplayName("a temple with recipes and the test volunteer gets the week, the event, the shifts and two recorded days")
	void theReseedPlansAWeek() throws SQLException {
		String a = "(SELECT id FROM tenants WHERE slug = 'rebuild-a')";
		String today = "(now() AT TIME ZONE 'Asia/Kolkata')::date";

		assertThat(countOf("SELECT count(*) FROM meal_plan_days WHERE tenant_id = " + a))
				.as("today-2 .. today+6").isEqualTo(9);
		assertThat(countOf("""
				SELECT count(DISTINCT d.plan_date) FROM meals m JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
				WHERE m.tenant_id = %s AND d.plan_date BETWEEN %s AND %s + 6 AND m.event_name IS NULL
				""".formatted(a, today, today))).as("seven days ahead").isEqualTo(7);
		// Breakfast, Lunch and Dinner on every one of the nine days; the fixture's temple has all three.
		assertThat(countOf("""
				SELECT count(*) FROM meals m JOIN meal_kinds mk ON mk.id = m.meal_kind_id
				WHERE m.tenant_id = %s AND lower(mk.name) IN ('breakfast', 'lunch', 'dinner') AND m.event_name IS NULL
				""".formatted(a))).isEqualTo(27);
		assertThat(countOf("""
				SELECT count(*) FROM meals m JOIN meal_kinds mk ON mk.id = m.meal_kind_id
				WHERE m.tenant_id = %s AND mk.is_event AND m.event_name IS NOT NULL
				""".formatted(a))).as("one named event").isEqualTo(1);

		assertThat(countOf("""
				SELECT count(*) FROM (
					SELECT m.id, count(md.id) AS dishes, count(DISTINCT md.recipe_id) AS recipes
					FROM meals m LEFT JOIN meal_dishes md ON md.meal_id = m.id
					WHERE m.tenant_id = %s GROUP BY m.id) x
				WHERE x.dishes NOT BETWEEN 3 AND 4 OR x.recipes <> x.dishes
				""".formatted(a))).as("meals without 3 or 4 distinct dishes").isZero();

		// The meal shift, with the volunteer on it.
		assertThat(strings("""
				SELECT s.title || '|' || u.email || '|' || (s.shift_date = d.plan_date)
				FROM shifts s
				JOIN meals m ON m.id = s.meal_id
				JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
				JOIN shift_signups su ON su.shift_id = s.id AND su.released_at IS NULL
				JOIN users u ON u.id = su.volunteer_user_id
				WHERE s.tenant_id = %s
				""".formatted(a))).containsExactly("Kitchen help for Lunch|" + VOLUNTEER_EMAIL + "|true");
		assertThat(strings("SELECT title FROM shifts WHERE meal_id IS NULL AND tenant_id = " + a + " ORDER BY title"))
				.containsExactly("Crowd control", "Garland making");

		// Two recorded days, card numbers from 1.
		assertThat(strings("""
				SELECT DISTINCT d.plan_date - %s FROM meals m JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
				WHERE m.tenant_id = %s AND m.recorded_at IS NOT NULL ORDER BY 1
				""".formatted(today, a))).containsExactly("-2", "-1");
		assertThat(strings("""
				SELECT right(card_number, 4) FROM meals
				WHERE tenant_id = %s AND card_number IS NOT NULL ORDER BY right(card_number, 4)
				""".formatted(a))).containsExactly("0001", "0002", "0003", "0004", "0005", "0006");
		assertThat(countOf("""
				SELECT count(*) FROM meal_dishes md JOIN meals m ON m.id = md.meal_id
				WHERE m.tenant_id = %s AND m.recorded_at IS NOT NULL
				  AND NOT (md.status = 'COOKED' AND md.actual_servings IS NOT NULL AND md.cooked_at IS NOT NULL)
				""".formatted(a))).as("dishes of a recorded meal that are not cooked").isZero();
		assertThat(countOf("SELECT last_number FROM meal_card_sequence WHERE tenant_id = " + a)).isEqualTo(6);

		assertThat(movementsAfterReseed)
				.as("the reseed writes no stock movements")
				.isEqualTo(movementsAfterReset);
	}

	@Test
	@DisplayName("a temple without the test volunteer gets its meal shift with nobody signed up")
	void theReseedWithoutTheVolunteer() throws SQLException {
		String b = "(SELECT id FROM tenants WHERE slug = 'rebuild-b')";
		assertThat(countOf("SELECT count(*) FROM shifts WHERE meal_id IS NOT NULL AND tenant_id = " + b)).isEqualTo(1);
		assertThat(countOf("SELECT count(*) FROM shift_signups WHERE tenant_id = " + b)).isZero();
	}

	@Test
	@DisplayName("a temple with no recipes of its own is given nothing")
	void theReseedLeavesATempleWithoutRecipesAlone() throws SQLException {
		String c = "(SELECT id FROM tenants WHERE slug = 'rebuild-bare')";
		for (String table : List.of("meal_plan_days", "meals", "meal_dishes", "shifts", "meal_card_sequence")) {
			assertThat(countOf("SELECT count(*) FROM " + table + " WHERE tenant_id = " + c))
					.as("%s for the temple with no recipes", table).isZero();
		}
	}

	// ---- The fixture ------------------------------------------------------------------------------

	/**
	 * Two temples in the shapes V134 rows really have, plus one with nothing but people, kinds and a
	 * sack of rice. {@code p_scale} doubles every meal draw in temple B, so the two temples'
	 * adjustments are different numbers.
	 */
	private void seedTemples() throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE FUNCTION pg_temp.seed_temple(p_slug text, p_volunteer_email text, p_scale numeric)
					RETURNS uuid AS $$
					DECLARE
					  v_t uuid; v_admin uuid; v_cook uuid; v_vol uuid; v_rice uuid; v_ghee uuid; v_dal uuid;
					  v_cat uuid; v_r1 uuid; v_r2 uuid; v_r3 uuid; v_r4 uuid;
					  v_b1 uuid := gen_random_uuid(); v_b2 uuid := gen_random_uuid();
					  v_bg uuid := gen_random_uuid(); v_bd uuid := gen_random_uuid();
					  v_d1 uuid; v_d2 uuid; v_move uuid; v_ms uuid; v_shift uuid; v_mshift uuid; v_sign uuid;
					  v_br uuid; v_vendor uuid; v_po uuid; v_pol uuid; v_gr uuid; v_rcpt uuid; v_notif uuid; v_doc uuid;
					BEGIN
					  INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					    VALUES (p_slug, p_slug, 12.97, 77.59, 'Asia/Kolkata') RETURNING id INTO v_t;
					  INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
					    VALUES (v_t, p_slug || '-admin', 'Admin', p_slug || '-admin@example.com', '+919876500111', 'TEMPLE_ADMIN')
					    RETURNING id INTO v_admin;
					  INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
					    VALUES (v_t, p_slug || '-cook', 'Cook', p_slug || '-cook@example.com', '+919876500112', 'KITCHEN_STAFF')
					    RETURNING id INTO v_cook;
					  INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
					    VALUES (v_t, p_slug || '-vol', 'Volunteer', p_volunteer_email, '+919876500113', 'VOLUNTEER')
					    RETURNING id INTO v_vol;
					  INSERT INTO meal_kinds (tenant_id, name, sort_order, default_ready_time, is_event) VALUES
					    (v_t, 'Breakfast', 10, '07:30', false), (v_t, 'Lunch', 20, '12:00', false),
					    (v_t, 'Dinner', 30, '19:30', false), (v_t, 'Event', 50, NULL, true);
					  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
					    VALUES (v_t, 'Rice', 'Grains', 'KG') RETURNING id INTO v_rice;
					  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
					    VALUES (v_t, 'Ghee', 'Dairy', 'L') RETURNING id INTO v_ghee;
					  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
					    VALUES (v_t, 'Dal', 'Pulses', 'KG') RETURNING id INTO v_dal;
					  INSERT INTO recipe_categories (tenant_id, name) VALUES (v_t, 'Mains') RETURNING id INTO v_cat;
					  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
					    VALUES (v_t, 'Khichdi', v_cat, 10, 'KG') RETURNING id INTO v_r1;
					  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
					    VALUES (v_t, 'Sambar', v_cat, 20, 'L') RETURNING id INTO v_r2;
					  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
					    VALUES (v_t, 'Halwa', v_cat, 5, 'KG') RETURNING id INTO v_r3;
					  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
					    VALUES (v_t, 'Payasam', v_cat, 8, 'L') RETURNING id INTO v_r4;

					  -- A delivery: vendor, order, receipt and the movement it made.
					  INSERT INTO vendors (tenant_id, name) VALUES (v_t, 'Vendor') RETURNING id INTO v_vendor;
					  INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by, sent_at)
					    VALUES (v_t, 'PO-1', v_vendor, 'RECEIVED', v_admin, now()) RETURNING id INTO v_po;
					  INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
					    VALUES (v_t, v_po, v_rice, 10, 'KG') RETURNING id INTO v_pol;
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reference_type, reference_id, actor_user_id, expiry_date)
					    VALUES (v_t, v_rice, v_b1, 10, 'KG', 'PO_RECEIPT', 'PURCHASE_ORDER', v_po, v_admin, DATE '2030-12-01')
					    RETURNING id INTO v_rcpt;
					  INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by)
					    VALUES (v_t, v_po, 'receipt-1', v_admin) RETURNING id INTO v_gr;
					  INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit,
					                                   batch_id, stock_movement_id)
					    VALUES (v_t, v_gr, v_pol, v_rice, 10, 'KG', v_b1, v_rcpt);
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
					    VALUES (v_t, v_rice, v_b2, 5000, 'GM', 'DONATION_IN_KIND', v_admin),
					           (v_t, v_ghee, v_bg, 4, 'L', 'DONATION_IN_KIND', v_admin),
					           (v_t, v_dal, v_bd, 3, 'KG', 'DONATION_IN_KIND', v_admin);

					  -- A spoilage adjustment and its correction: not about a meal, so both stay.
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reason_category, actor_user_id)
					    VALUES (v_t, v_dal, v_bd, -0.250, 'KG', 'ADJUSTMENT', 'SPOILAGE', v_admin) RETURNING id INTO v_move;
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reason_category, reference_type, reference_id, actor_user_id)
					    VALUES (v_t, v_dal, v_bd, 0.250, 'KG', 'ADJUSTMENT', 'COUNT_CORRECTION', 'CORRECTION', v_move, v_admin);

					  -- A cooked and recorded lunch of two dishes, and a planned event.
					  INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, recipe_id, target_yield, day_type, status,
					                          created_by, ready_by, actual_servings, consumed_quantity, cooked_at, adults)
					    VALUES (v_t, DATE '2026-09-10', 'Lunch', v_r1, 10, 'REGULAR', 'COOKED', v_cook, '12:00', 10, 9, now(), 100)
					    RETURNING id INTO v_d1;
					  INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, recipe_id, target_yield, day_type, status,
					                          created_by, ready_by, actual_servings, cooked_at, adults)
					    VALUES (v_t, DATE '2026-09-10', 'Lunch', v_r2, 20, 'REGULAR', 'COOKED', v_cook, '12:00', 20, now(), 100)
					    RETURNING id INTO v_d2;
					  INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, recipe_id, target_yield, day_type, created_by,
					                          ready_by, event_name)
					    VALUES (v_t, DATE '2026-09-20', 'Event', v_r3, 5, 'WEEKEND', v_cook, '17:00', 'Reading');

					  -- Its draws. Rice: 2 kg from the delivery, 500 g from the donation; the 2 kg corrected back
					  -- and 1.5 kg drawn again. Ghee: 1.25 L. Dal: cooked beyond what the books held, which is a
					  -- memorandum row and moves nothing (V116).
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reference_type, reference_id, actor_user_id)
					    VALUES (v_t, v_rice, v_b1, -2 * p_scale, 'KG', 'CONSUMPTION', 'MEAL_PLAN', v_d1, v_cook)
					    RETURNING id INTO v_move;
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reference_type, reference_id, actor_user_id)
					    VALUES (v_t, v_rice, v_b2, -500 * p_scale, 'GM', 'CONSUMPTION', 'MEAL_PLAN', v_d1, v_cook);
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reason_category, reference_type, reference_id, actor_user_id)
					    VALUES (v_t, v_rice, v_b1, 2 * p_scale, 'KG', 'ADJUSTMENT', 'COUNT_CORRECTION', 'CORRECTION', v_move, v_admin);
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reference_type, reference_id, actor_user_id)
					    VALUES (v_t, v_rice, v_b1, -1.5 * p_scale, 'KG', 'CONSUMPTION', 'MEAL_PLAN', v_d1, v_admin),
					           (v_t, v_ghee, v_bg, -1.25 * p_scale, 'L', 'CONSUMPTION', 'MEAL_PLAN', v_d2, v_cook),
					           (v_t, v_dal, v_bd, -3 * p_scale, 'KG', 'USED_BEYOND_RECORDED_STOCK', 'MEAL_PLAN', v_d2, v_cook);

					  INSERT INTO meal_services (tenant_id, plan_date, meal_kind, card_number, card_issued_at, card_version,
					                             recorded_at, recorded_by, corrected_at, corrected_by, correction_note)
					    VALUES (v_t, DATE '2026-09-10', 'Lunch', 'LC-2026-0007', now(), 1, now(), v_cook, now(), v_admin,
					            'The rice was over-recorded.')
					    RETURNING id INTO v_ms;
					  INSERT INTO meal_card_sequence (tenant_id, last_number) VALUES (v_t, 7);
					  INSERT INTO documents (tenant_id, kind, meal_service_id, status)
					    VALUES (v_t, 'JOB_CARD_PDF', v_ms, 'PENDING') RETURNING id INTO v_doc;
					  INSERT INTO documents (tenant_id, kind, recipe_id, status) VALUES (v_t, 'RECIPE_PDF', v_r1, 'READY');

					  -- A plain shift with a broadcast, and a shift for that lunch with a signup whose
					  -- attendance was marked, a waitlist and a reminder.
					  INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
					    VALUES (v_t, 'Garlands', DATE '2026-09-20', '06:00', '08:00', 5, v_admin) RETURNING id INTO v_shift;
					  INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by,
					                      meal_date, meal_kind)
					    VALUES (v_t, 'Cutting', DATE '2026-09-10', '09:00', '11:00', 1, v_admin, DATE '2026-09-10', 'Lunch')
					    RETURNING id INTO v_mshift;
					  INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id, attended, attendance_recorded_at)
					    VALUES (v_t, v_mshift, v_vol, true, now()) RETURNING id INTO v_sign;
					  INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id) VALUES (v_t, v_mshift, v_cook);
					  INSERT INTO notifications (tenant_id, recipient_user_id, recipient_label, to_phone, template, preferred_channel)
					    VALUES (v_t, v_vol, 'Volunteer', '+919876500113', 'SHIFT_REMINDER', 'WHATSAPP') RETURNING id INTO v_notif;
					  INSERT INTO shift_reminders (tenant_id, shift_id, signup_id, offset_minutes, notification_id)
					    VALUES (v_t, v_mshift, v_sign, 1440, v_notif);
					  INSERT INTO shift_broadcasts (tenant_id, shift_id, message, sent_by)
					    VALUES (v_t, v_shift, 'Please bring scissors.', v_admin) RETURNING id INTO v_br;
					  INSERT INTO shift_broadcast_recipients (tenant_id, broadcast_id, recipient_user_id, notification_id)
					    VALUES (v_t, v_br, v_vol, v_notif);
					  INSERT INTO audit_events (tenant_id, actor_user_id, actor_label, action, entity_type, entity_id)
					    VALUES (v_t, v_admin, 'Admin', 'MEAL_PLAN_CREATED', 'MEAL_PLAN', v_d1);

					  -- The schedule, as Quartz stores it: a reminder for the meal shift with its trigger, a render
					  -- still queued for the job card, and a notification send that is not the reset's business.
					  INSERT INTO qrtz_job_details (sched_name, job_name, job_group, job_class_name, is_durable,
					                                is_nonconcurrent, is_update_data, requests_recovery, job_data)
					    VALUES ('kms', v_sign || ':1440', 'shiftrem-' || v_mshift, 'SendShiftReminderJob', false, false, false, true,
					            convert_to('kms.tenantId' || v_t, 'UTF8')),
					           ('kms', 'generate-document-' || v_doc, 'DEFAULT', 'GenerateDocumentJob', false, false, false, true,
					            convert_to('kms.tenantId' || v_t, 'UTF8')),
					           ('kms', 'send-' || v_notif, 'DEFAULT', 'SendNotificationJob', false, false, false, true,
					            convert_to('kms.tenantId' || v_t, 'UTF8'));
					  INSERT INTO qrtz_triggers (sched_name, trigger_name, trigger_group, job_name, job_group, trigger_state,
					                             trigger_type, start_time)
					    VALUES ('kms', 'reminder-' || v_sign, 'DEFAULT', v_sign || ':1440', 'shiftrem-' || v_mshift,
					            'WAITING', 'SIMPLE', 0);
					  INSERT INTO qrtz_simple_triggers (sched_name, trigger_name, trigger_group, repeat_count,
					                                    repeat_interval, times_triggered)
					    VALUES ('kms', 'reminder-' || v_sign, 'DEFAULT', 0, 0, 0);
					  RETURN v_t;
					END $$ LANGUAGE plpgsql
					""");
			statement.execute("SELECT pg_temp.seed_temple('rebuild-a', '" + VOLUNTEER_EMAIL + "', 1)");
			statement.execute("SELECT pg_temp.seed_temple('rebuild-b', 'rebuild-b-volunteer@example.com', 2)");

			// A job every temple shares, which carries no temple and must never be matched.
			statement.execute("""
					INSERT INTO qrtz_job_details (sched_name, job_name, job_group, job_class_name, is_durable,
					                              is_nonconcurrent, is_update_data, requests_recovery, job_data)
					VALUES ('kms', 'global-sweep', 'DEFAULT', 'SweepJob', true, false, false, false, NULL)
					""");

			// The temple with no recipes: people, kinds and stock, and no meals because it cannot have any.
			statement.execute("""
					WITH t AS (
						INSERT INTO tenants (slug, name, latitude, longitude, timezone)
						VALUES ('rebuild-bare', 'Bare Temple', 12.97, 77.59, 'Asia/Kolkata') RETURNING id),
					u AS (
						INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
						SELECT id, 'bare-admin', 'Bare Admin', 'bare-admin@example.com', '+919876500121', 'TEMPLE_ADMIN'
						FROM t RETURNING id, tenant_id),
					k AS (
						INSERT INTO meal_kinds (tenant_id, name, sort_order, default_ready_time)
						SELECT id, 'Lunch', 20, '12:00' FROM t),
					i AS (
						INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
						SELECT id, 'Rice', 'Grains', 'KG' FROM t RETURNING id, tenant_id)
					INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
					SELECT i.tenant_id, i.id, gen_random_uuid(), 7, 'KG', 'DONATION_IN_KIND', u.id FROM i JOIN u USING (tenant_id)
					""");
		}
	}

	// ---- Helpers ----------------------------------------------------------------------------------

	private Map<String, BigDecimal> onHand() throws SQLException {
		return decimals("""
				SELECT t.slug || '|' || i.name || '|' || m.batch_id,
				       SUM(to_on_hand_qty(m.quantity, m.unit, m.movement_type))
				FROM stock_movements m
				JOIN ingredients i ON i.id = m.ingredient_id
				JOIN tenants t ON t.id = m.tenant_id
				GROUP BY 1
				""");
	}

	private static void insertMeal(Connection app, String temple, String dayId, String kindId, String eventName)
			throws SQLException {
		try (PreparedStatement ps = app.prepareStatement("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, event_name, ready_by)
				VALUES (?::uuid, ?::uuid, ?::uuid, ?, TIME '17:00')
				""")) {
			ps.setString(1, temple);
			ps.setString(2, dayId);
			ps.setString(3, kindId);
			ps.setString(4, eventName);
			ps.executeUpdate();
		}
	}

	private static void insertShift(Connection app, String temple, String mealId, String createdBy, String status)
			throws SQLException {
		try (PreparedStatement ps = app.prepareStatement("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by, meal_id, status)
				VALUES (?::uuid, 'Another kitchen shift', DATE '2030-01-01', TIME '09:00', TIME '11:00', 2, ?::uuid, ?::uuid, ?)
				""")) {
			ps.setString(1, temple);
			ps.setString(2, createdBy);
			ps.setString(3, mealId);
			ps.setString(4, status);
			ps.executeUpdate();
		}
	}

	/** The application role, adopting a temple the way TenantAwareDataSource does: transaction-local. */
	private static void adopt(Connection app, String temple) throws SQLException {
		try (PreparedStatement ps = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
			ps.setString(1, temple);
			ps.executeQuery().close();
		}
	}

	private String idOf(String slug) throws SQLException {
		return strings("SELECT id::text FROM tenants WHERE slug = '" + slug + "'").get(0);
	}

	private static String oneString(Connection connection, String sql) throws SQLException {
		try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			assertThat(rs.next()).as("a row from: %s", sql).isTrue();
			return rs.getString(1);
		}
	}

	private static long count(Connection connection, String sql) throws SQLException {
		try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private long countOf(String sql) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE)) {
			return count(connection, sql);
		}
	}

	private List<String> strings(String sql) throws SQLException {
		List<String> out = new ArrayList<>();
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement s = connection.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	private Map<String, BigDecimal> decimals(String sql) throws SQLException {
		Map<String, BigDecimal> out = new TreeMap<>();
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement s = connection.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.put(rs.getString(1), rs.getBigDecimal(2));
			}
		}
		return out;
	}

	private void migrateTo(String version) {
		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(version)
				.load()
				.migrate();
	}

	private void migrateToTheEnd() {
		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema here too, exactly as it does in the real one, so a
		// migration that only works when row-level security is bypassed still fails.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private static Connection appConnection() throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(DATABASE));
		dataSource.setUsername(APP_ROLE);
		dataSource.setPassword(APP_PASSWORD);
		return dataSource.getConnection();
	}

	private static Connection superuserConnectionTo(String database) throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(database));
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource.getConnection();
	}

	private static String url() {
		return urlFor(DATABASE);
	}

	private static String urlFor(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(
				POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database);
	}
}
