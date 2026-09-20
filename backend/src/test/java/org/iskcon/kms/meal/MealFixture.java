package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Meals written straight into the database, for tests that need a meal to exist rather than a meal
 * to be planned (D-27).
 *
 * <p><strong>Why one helper and not a copy in every class.</strong> Before D-27 a meal was one
 * INSERT into {@code meal_plans}, and some twenty test classes each carried their own. A meal is now
 * three rows — its day, the meal, its dishes — related by foreign keys and a unique index on the
 * meal's day, kind id and folded event name. Twenty private copies of that would be twenty places
 * to get the fold or the order of inserts wrong, and a fixture that disagrees with the index about
 * which rows are one meal proves nothing about code that relies on it.
 *
 * <p><strong>Find, or create — the same identity the application uses.</strong> {@link #meal} returns
 * the meal already on that day with that kind and that event name compared without regard to case,
 * so a test that writes two dishes for one Lunch gets one meal of two dishes, exactly as the planner
 * would make it.
 *
 * <p>Every method takes a privileged connection ({@code adminDataSource()}), because a fixture that
 * spans temples cannot be built through a connection scoped to one, and names the temple on every
 * row itself.
 */
public final class MealFixture {

	private MealFixture() {
	}

	/** The temple's kind of this name, or null where it has none. */
	public static UUID kindId(JdbcTemplate admin, UUID tenant, String kindName) {
		return admin.query("SELECT id FROM meal_kinds WHERE tenant_id = ? AND lower(name) = lower(?)",
				(rs, n) -> rs.getObject("id", UUID.class), tenant, kindName).stream().findFirst().orElse(null);
	}

	/**
	 * The temple's kind of this name, created if it has none.
	 *
	 * <p>For the many fixtures that never needed kinds while a meal stored its kind as text — a
	 * costing test planning an "Annadana", a stock test planning a "Lunch". A meal now points at a
	 * kind row, so one has to exist. Created as the seeding creates them apart from the time: an
	 * "Event" is an event, nothing else is, and no kind asks for an occasion.
	 */
	public static UUID ensureKind(JdbcTemplate admin, UUID tenant, String kindName) {
		UUID id = kindId(admin, tenant, kindName);
		if (id != null) {
			return id;
		}
		admin.update("""
				INSERT INTO meal_kinds (tenant_id, name, sort_order, is_event, needs_occasion)
				VALUES (?, ?, 90, ?, false)
				ON CONFLICT (tenant_id, lower(name)) DO NOTHING
				""", tenant, kindName, "event".equalsIgnoreCase(kindName));
		return kindId(admin, tenant, kindName);
	}

	/** The temple's plan for a date, created REGULAR if it has none. */
	public static UUID day(JdbcTemplate admin, UUID tenant, LocalDate date) {
		return day(admin, tenant, date, "REGULAR");
	}

	/** The temple's plan for a date, created with this day type if it has none. */
	public static UUID day(JdbcTemplate admin, UUID tenant, LocalDate date, String dayType) {
		admin.update("""
				INSERT INTO meal_plan_days (tenant_id, plan_date, day_type) VALUES (?, ?, ?)
				ON CONFLICT (tenant_id, plan_date) DO NOTHING
				""", tenant, date, dayType);
		return admin.queryForObject(
				"SELECT id FROM meal_plan_days WHERE tenant_id = ? AND plan_date = ?", UUID.class, tenant, date);
	}

	/** A main meal — no event name — found or created. */
	public static UUID meal(JdbcTemplate admin, UUID tenant, LocalDate date, String kindName, LocalTime readyBy) {
		return meal(admin, tenant, date, kindName, null, readyBy);
	}

	/**
	 * The meal on that day of that kind and event name, found or created.
	 *
	 * <p>The kind is the temple's seeded one where it has been seeded, and is created otherwise
	 * ({@link #ensureKind}). An existing meal keeps its own ready-by; set anything else with {@link #set}.
	 */
	public static UUID meal(
			JdbcTemplate admin, UUID tenant, LocalDate date, String kindName, String eventName, LocalTime readyBy) {
		UUID dayId = day(admin, tenant, date);
		UUID kind = ensureKind(admin, tenant, kindName);
		UUID existing = admin.query("""
				SELECT id FROM meals
				WHERE meal_plan_day_id = ? AND meal_kind_id = ?
				  AND lower(COALESCE(event_name, '')) = lower(COALESCE(CAST(? AS text), ''))
				""", (rs, n) -> rs.getObject("id", UUID.class), dayId, kind, eventName)
				.stream().findFirst().orElse(null);
		if (existing != null) {
			return existing;
		}
		UUID mealId = admin.queryForObject("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, event_name, ready_by)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, dayId, kind, eventName, readyBy);
		section(admin, tenant, mealId, null);
		return mealId;
	}

	/**
	 * The meal's kitchen — its one section — created in the temple's planner kitchen if it has none
	 * (Epic 12, V150). Every meal has at least one kitchen and every dish sits under one of its meal's
	 * kitchens (a composite foreign key), so a fixture meal needs a section as surely as a planned one.
	 *
	 * <p>The kitchen is chosen as V150 and {@code KitchenService.seedMainKitchenForCurrentTenant} choose
	 * it — the main kitchen if it plans meals, else the first planner kitchen by name — and created as
	 * they create it where the temple has none, so a fixture temple ends up shaped like a real one.
	 *
	 * @param createdBy who a kitchen made here is recorded as created by; null takes the temple's
	 *                  earliest Temple Admin, else its earliest user
	 */
	public static UUID section(JdbcTemplate admin, UUID tenant, UUID mealId, UUID createdBy) {
		UUID existing = admin.query("""
				SELECT kitchen_id FROM meal_kitchens WHERE meal_id = ? ORDER BY created_at, kitchen_id LIMIT 1
				""", (rs, n) -> rs.getObject("kitchen_id", UUID.class), mealId).stream().findFirst().orElse(null);
		if (existing != null) {
			return existing;
		}
		UUID kitchen = plannerKitchen(admin, tenant, createdBy);
		admin.update("""
				INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id)
				SELECT tenant_id, id, ? FROM meals WHERE id = ?
				""", kitchen, mealId);
		return kitchen;
	}

	/** The temple's planner kitchen, created 'Main kitchen' (main if it has none) where it has none. */
	public static UUID plannerKitchen(JdbcTemplate admin, UUID tenant, UUID createdBy) {
		UUID found = admin.query("""
				SELECT id FROM kitchens
				WHERE tenant_id = ? AND status = 'ACTIVE' AND uses_meal_planner
				ORDER BY is_main DESC, lower(name), id LIMIT 1
				""", (rs, n) -> rs.getObject("id", UUID.class), tenant).stream().findFirst().orElse(null);
		if (found != null) {
			return found;
		}
		UUID creator = createdBy != null ? createdBy : admin.queryForObject("""
				SELECT id FROM users WHERE tenant_id = ?
				ORDER BY (role = 'TEMPLE_ADMIN') DESC, created_at, id LIMIT 1
				""", UUID.class, tenant);
		String name = "Main kitchen";
		for (int attempt = 1; admin.queryForObject(
				"SELECT count(*) FROM kitchens WHERE tenant_id = ? AND lower(name) = lower(?)",
				Integer.class, tenant, name) > 0; attempt++) {
			name = attempt == 1 ? "Main kitchen (meals)" : "Main kitchen (meals " + attempt + ")";
		}
		boolean main = admin.queryForObject(
				"SELECT count(*) FROM kitchens WHERE tenant_id = ? AND is_main", Integer.class, tenant) == 0;
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, ?, ?, true, 'ACTIVE', ?) RETURNING id
				""", UUID.class, tenant, name, main, creator);
	}

	/** Sets the head count on a meal; nulls are written as nulls. */
	public static void headCount(JdbcTemplate admin, UUID mealId, Integer adults, Integer children, Integer seniors) {
		admin.update("UPDATE meals SET adults = ?, children = ?, seniors = ? WHERE id = ?",
				adults, children, seniors, mealId);
	}

	/**
	 * Sets one column on a meal. For the handful of whole-meal facts a test needs beyond the head
	 * count — an event going outside, a crew figure, an occasion — without a method per column.
	 *
	 * <p>{@code crew_required} is no longer a column of {@code meals} (V151): People needed is each
	 * kitchen's. Asked for by that name, it is set on the meal's one section, which is what a fixture
	 * meal has and what the old single figure meant.
	 */
	public static void set(JdbcTemplate admin, UUID mealId, String column, Object value) {
		if (!column.matches("[a-z_]+")) {
			throw new IllegalArgumentException("Not a column name: " + column);
		}
		if ("crew_required".equals(column)) {
			admin.update("UPDATE meal_kitchens SET crew_required = ? WHERE meal_id = ?", value, mealId);
			return;
		}
		admin.update("UPDATE meals SET " + column + " = ? WHERE id = ?", value, mealId);
	}

	/** One PLANNED dish of a meal. Answers with the dish's id. */
	public static UUID dish(JdbcTemplate admin, UUID tenant, UUID mealId, UUID recipeId, BigDecimal targetYield,
			UUID createdBy) {
		return dish(admin, tenant, mealId, recipeId, targetYield, "PLANNED", createdBy);
	}

	/** One dish of a meal in the given status. Answers with the dish's id. */
	public static UUID dish(JdbcTemplate admin, UUID tenant, UUID mealId, UUID recipeId, BigDecimal targetYield,
			String status, UUID createdBy) {
		UUID kitchen = section(admin, tenant, mealId, createdBy);
		return admin.queryForObject("""
				INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by, kitchen_id)
				VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, mealId, recipeId, targetYield, status, createdBy, kitchen);
	}

	/**
	 * The old single-row plan, as most fixtures used it: one dish on a main meal, found or created.
	 * Answers with the <em>dish's</em> id, which is what the stock ledger points at.
	 */
	public static UUID plan(JdbcTemplate admin, UUID tenant, LocalDate date, String kindName, LocalTime readyBy,
			UUID recipeId, BigDecimal targetYield, String status, UUID createdBy) {
		UUID mealId = meal(admin, tenant, date, kindName, readyBy);
		return dish(admin, tenant, mealId, recipeId, targetYield, status, createdBy);
	}

	/** The meal a dish belongs to. */
	public static UUID mealOf(JdbcTemplate admin, UUID dishId) {
		return admin.queryForObject("SELECT meal_id FROM meal_dishes WHERE id = ?", UUID.class, dishId);
	}

	/**
	 * Removes every meal, dish, series, day, card counter and meal kind, children first. Shifts
	 * pointing at a meal must be removed before this (their foreign key is RESTRICT); job-card
	 * documents go with their meal (CASCADE). Kinds are last because a meal holds its kind (RESTRICT),
	 * and removing them here means a class that created a kind on demand cannot leave it behind to hold
	 * its temple down.
	 *
	 * <p>Series (T-307) go straight after the meals that point at them, and for the same reason as the
	 * kinds: a series holds its temple with a RESTRICT key, so a test that repeated an event and cleaned
	 * up with this helper alone used to fail at {@code DELETE FROM tenants} (T-310).
	 */
	public static void deleteAll(JdbcTemplate admin) {
		admin.execute("DELETE FROM meal_dishes");
		// A meal's kitchens (V150) would go with it by cascade; said here so the order is on the page.
		admin.execute("DELETE FROM meal_kitchens");
		admin.execute("DELETE FROM meals");
		admin.execute("DELETE FROM meal_series");
		admin.execute("DELETE FROM meal_plan_days");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_kinds");
		// The kitchens a meal needed (V150) — made here by section(), or by the planner's own seed when a
		// test planned through the API — hold their temple and their creator with RESTRICT keys, so a
		// class cleaning up with this helper would otherwise fail at DELETE FROM users. Only kitchens
		// nothing else holds: one a staff member works in, or a request was raised by, is the class's
		// own to remove in its own order.
		admin.execute("""
				DELETE FROM kitchens k
				WHERE NOT EXISTS (SELECT 1 FROM staff_profiles sp WHERE sp.kitchen_id = k.id)
				  AND NOT EXISTS (SELECT 1 FROM ingredient_requests r WHERE r.kitchen_id = k.id)
				""");
	}
}
