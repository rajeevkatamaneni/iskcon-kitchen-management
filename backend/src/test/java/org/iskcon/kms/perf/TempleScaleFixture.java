package org.iskcon.kms.perf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds one temple at a stated scale, the way the application builds one: <strong>as the
 * unprivileged application role, with {@code app.tenant_id} set, so every insert is checked by the
 * Row-Level Security policy it will be read back through.</strong>
 *
 * <p><strong>Why that is not a detail.</strong> CLAUDE.md's first trap is that a superuser bypasses
 * RLS entirely, so a fixture seeded as one proves nothing about the query the application actually
 * runs — and the plan the application gets is not the plan a superuser gets. Every tenant-owned
 * table in this schema carries {@code USING (tenant_id = current_setting('app.tenant_id'))}, which
 * PostgreSQL folds into the query as an extra qualification. Measured as a superuser, that
 * qualification is absent and the numbers are quietly optimistic. Everything below therefore goes
 * through {@link #appConnection()}, which is {@code kms_app}: no DDL, no BYPASSRLS, exactly what
 * Cloud Run connects as.
 *
 * <p><strong>What is not created here, and why.</strong> The {@code tenants} row and the two
 * {@code users} rows are the caller's job, created as the superuser. That is not a convenience: a
 * tenant is not tenant-owned data, and the application never creates one from inside a tenant
 * context. Everything a temple accumulates by operating — its catalogue, its recipes, its plan, its
 * vendors, its orders and its ledger — is created here, under RLS.
 *
 * <p><strong>Set-at-a-time, not row-at-a-time.</strong> The large tables are filled by a single
 * {@code INSERT ... SELECT} over {@code generate_series}, so five years of ledger is one statement
 * rather than 146,000 round trips. The row values are deterministic functions of the row's position,
 * which means two runs at the same scale produce the same fixture and a number can be compared with
 * a number taken last week.
 */
final class TempleScaleFixture {

	private final String jdbcUrl;
	private final String appUser;
	private final String appPassword;

	TempleScaleFixture(String jdbcUrl, String appUser, String appPassword) {
		this.jdbcUrl = jdbcUrl;
		this.appUser = appUser;
		this.appPassword = appPassword;
	}

	/**
	 * What the fixture actually put in the database — every figure read back with {@code count(*)}
	 * through the RLS-constrained application connection, never inferred from the parameters.
	 *
	 * <p>That is the whole of the negative control this task was asked for. A generator that wrote a
	 * hundred rows where it claimed a hundred thousand would produce a fast, meaningless timing and
	 * pass every other check in the harness; it cannot pass this one, because these numbers come out
	 * of the table rather than out of the arithmetic that was supposed to fill it.
	 */
	record Counts(
			int ingredients,
			int inventoryItems,
			int recipes,
			int recipeIngredients,
			int mealPlansCooked,
			int mealPlansPlanned,
			int stockMovements,
			int vendors,
			int vendorSupplies,
			int purchaseOrders,
			int purchaseOrderLines,
			Duration buildTime) {

		int mealPlans() {
			return mealPlansCooked + mealPlansPlanned;
		}
	}

	// -------------------------------------------------------------------------------------------

	Counts generate(UUID tenantId, UUID actorUserId, TempleScale scale) {
		long startedAt = System.nanoTime();
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			setTenant(connection, tenantId);

			catalogue(connection, tenantId, scale);
			recipes(connection, tenantId, scale);
			vendors(connection, tenantId, scale);
			plan(connection, tenantId, actorUserId, scale);
			ledger(connection, tenantId, actorUserId, scale);
			purchaseOrders(connection, tenantId, actorUserId, scale);

			connection.commit();

			// ANALYZE so the planner is working from real statistics rather than from the defaults it
			// assumes for a table it has never seen. Without this the first EXPLAIN of the run reports
			// a plan chosen on a guess of 1000 rows, which is a plan the production database would
			// never choose — autovacuum would have visited the table long before it reached this size.
			// Run as the schema owner is not required: ANALYZE is permitted to a table's readers here
			// because kms_app holds the privileges V1 granted it, and where it is not, the statement
			// warns rather than fails, which is why it is deliberately not wrapped in a check.
			try (Statement statement = connection.createStatement()) {
				statement.execute("ANALYZE stock_movements, meal_plans, recipe_ingredients, ingredients,"
						+ " inventory_items, purchase_order_lines, purchase_orders, vendor_supplies");
			}
			connection.commit();

			return count(connection, Duration.ofNanos(System.nanoTime() - startedAt));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not build the fixture at " + scale, e);
		}
	}

	/** Re-reads the counts for a tenant without generating anything. */
	Counts recount(UUID tenantId) {
		try (Connection connection = appConnection()) {
			setTenant(connection, tenantId);
			return count(connection, Duration.ZERO);
		} catch (SQLException e) {
			throw new IllegalStateException("Could not count the fixture", e);
		}
	}

	/**
	 * Throws unless the database holds exactly what {@code scale} says it should.
	 *
	 * <p>Compared field by field and reported all at once rather than on the first mismatch, because
	 * the interesting failure is the shape of the shortfall — one table short is a bug in one
	 * statement, every table short is a fixture that never ran.
	 */
	static void verify(Counts counts, TempleScale scale) {
		Map<String, int[]> mismatches = new LinkedHashMap<>();
		check(mismatches, "ingredients", counts.ingredients(), scale.ingredients());
		check(mismatches, "inventory_items", counts.inventoryItems(), scale.inventoryItems());
		check(mismatches, "recipes", counts.recipes(), scale.recipes());
		check(mismatches, "recipe_ingredients", counts.recipeIngredients(), scale.expectedRecipeIngredients());
		check(mismatches, "meal_plans (COOKED)", counts.mealPlansCooked(), scale.expectedCookedPlans());
		check(mismatches, "meal_plans (PLANNED)", counts.mealPlansPlanned(), scale.expectedPlannedPlans());
		check(mismatches, "stock_movements", counts.stockMovements(), scale.expectedStockMovements());
		check(mismatches, "vendors", counts.vendors(), scale.vendors());
		check(mismatches, "vendor_supplies", counts.vendorSupplies(), scale.expectedVendorSupplies());
		check(mismatches, "purchase_orders", counts.purchaseOrders(), scale.purchaseOrders());
		check(mismatches, "purchase_order_lines", counts.purchaseOrderLines(), scale.expectedPurchaseOrderLines());

		if (mismatches.isEmpty()) {
			return;
		}
		StringBuilder message = new StringBuilder(
				"The fixture did not produce what it claimed. A timing taken over this data would be"
						+ " meaningless. Table: counted vs expected —");
		mismatches.forEach((table, pair) ->
				message.append("\n  ").append(table).append(": counted ").append(pair[0])
						.append(", expected ").append(pair[1])
						.append(" (short by ").append(pair[1] - pair[0]).append(')'));
		throw new IllegalStateException(message.toString());
	}

	private static void check(Map<String, int[]> into, String table, int counted, int expected) {
		if (counted != expected) {
			into.put(table, new int[] {counted, expected});
		}
	}

	/** Runs {@code EXPLAIN (ANALYZE, BUFFERS)} as the application role, tenant set, and returns it. */
	List<String> explain(UUID tenantId, String sql) {
		try (Connection connection = appConnection()) {
			setTenant(connection, tenantId);
			List<String> out = new ArrayList<>();
			try (Statement statement = connection.createStatement();
					ResultSet rs = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
			return out;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not EXPLAIN: " + sql, e);
		}
	}

	/**
	 * Per-table scan and row-fetch counters, straight out of {@code pg_stat_all_tables}.
	 *
	 * <p>Taken before and after a single request, the difference says <em>how many times one page
	 * load reads a table</em> — which is a fact about the running application, obtained without
	 * reading a line of its source or naming one of its private methods. That matters here because
	 * the wall time and the EXPLAIN together leave a gap: if the page costs 437 ms and the query the
	 * brief singled out costs 67, something has to account for the rest, and "how many times is the
	 * ledger scanned" is the cheapest honest way to ask.
	 *
	 * <p>The counters are cluster-wide rather than tenant-scoped, which is fine and worth saying: this
	 * database holds exactly one temple while the harness runs.
	 */
	Map<String, long[]> tableStats(List<String> tables) {
		try (Connection connection = appConnection()) {
			Map<String, long[]> out = new LinkedHashMap<>();
			try (PreparedStatement ps = connection.prepareStatement("""
					SELECT relname, coalesce(seq_scan, 0) AS seq_scan, coalesce(idx_scan, 0) AS idx_scan,
					       coalesce(seq_tup_read, 0) AS seq_tup_read, coalesce(idx_tup_fetch, 0) AS idx_tup_fetch
					FROM pg_stat_all_tables
					WHERE schemaname = 'public' AND relname = ANY (?)
					""")) {
				ps.setArray(1, connection.createArrayOf("text", tables.toArray()));
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						out.put(rs.getString("relname"), new long[] {
								rs.getLong("seq_scan"), rs.getLong("idx_scan"),
								rs.getLong("seq_tup_read"), rs.getLong("idx_tup_fetch")});
					}
				}
			}
			return out;
		} catch (SQLException e) {
			throw new IllegalStateException("Could not read pg_stat_all_tables", e);
		}
	}

	Connection appConnection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl, appUser, appPassword);
	}

	static void setTenant(Connection connection, UUID tenantId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
			ps.setString(1, tenantId.toString());
			ps.execute();
		}
	}

	// --- the generators -------------------------------------------------------------------------

	private void catalogue(Connection connection, UUID tenantId, TempleScale scale) throws SQLException {
		// Names are zero-padded so that ORDER BY name is a stable, total order. Every generator below
		// picks its ingredient by position in that order, so padding is what makes the fixture
		// reproducible rather than dependent on how PostgreSQL happens to collate "Ingredient 10".
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				SELECT ?::uuid,
				       'Perf Ingredient ' || lpad(g.n::text, 5, '0'),
				       (ARRAY['Grains', 'Pulses', 'Vegetables', 'Dairy', 'Spices', 'Oils'])[1 + (g.n % 6)],
				       'KG'
				FROM generate_series(1, ?) AS g(n)
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.ingredients());
			ps.executeUpdate();
		}

		// Not every ingredient is stocked — staging tracks 116 of its 150 — and the gap is kept because
		// the threshold stream reads inventory_items while the shortfall stream reads ingredients, so a
		// fixture where the two sets are identical would hide any difference between them.
		//
		// The thresholds cycle through five values against an opening stock of 120 KG, which leaves
		// roughly two items in five below their reorder level once the safety factor is applied. That
		// is what makes the resulting list a realistic length rather than empty or the whole catalogue.
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				SELECT ?::uuid, t.id, 20 + (t.rn % 5) * 40
				FROM (SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn FROM ingredients) t
				WHERE t.rn < ?
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.inventoryItems());
			ps.executeUpdate();
		}
	}

	private void recipes(Connection connection, UUID tenantId, TempleScale scale) throws SQLException {
		// The temple's own workbook classifies by course, so the categories are courses.
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO recipe_categories (tenant_id, name)
				SELECT ?::uuid, c
				FROM unnest(ARRAY['Rice', 'Dal', 'Sabji', 'Sweets', 'Breakfast']) AS c
				""")) {
			ps.setString(1, tenantId.toString());
			ps.executeUpdate();
		}

		try (PreparedStatement ps = connection.prepareStatement("""
				WITH cats AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM recipe_categories
				)
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				SELECT ?::uuid, 'Perf Recipe ' || lpad(g.n::text, 4, '0'), c.id, 100, 'KG'
				FROM generate_series(1, ?) AS g(n)
				JOIN cats c ON c.rn = (g.n % c.total)
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.recipes());
			ps.executeUpdate();
		}

		// Each recipe names `ingredientsPerRecipe` distinct ingredients, chosen by a stride of 7 from a
		// per-recipe offset. A stride rather than a random pick so the set is reproducible, and 7 so
		// that no two lines of one recipe collide for any realistic catalogue size — which matters
		// because a duplicated ingredient on one recipe would double that ingredient's demand and make
		// the shopping list wrong in a way nothing in this harness would notice.
		try (PreparedStatement ps = connection.prepareStatement("""
				WITH numbered_recipes AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn FROM recipes
				),
				numbered_ingredients AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn FROM ingredients
				)
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				SELECT ?::uuid, r.id, i.id, 1.500, 'KG', s.k
				FROM numbered_recipes r
				CROSS JOIN generate_series(0, ? - 1) AS s(k)
				JOIN numbered_ingredients i
				  ON i.rn = ((r.rn * 13 + s.k * 7) % (SELECT count(*) FROM numbered_ingredients))
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.ingredientsPerRecipe());
			ps.executeUpdate();
		}
	}

	private void vendors(Connection connection, UUID tenantId, TempleScale scale) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO vendors (tenant_id, name, phone)
				SELECT ?::uuid, 'Perf Vendor ' || lpad(g.n::text, 3, '0'),
				       '+9198' || lpad(g.n::text, 8, '0')
				FROM generate_series(1, ?) AS g(n)
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.vendors());
			ps.executeUpdate();
		}

		// One preferred vendor per ingredient — the shopping list's vendor suggestion reads exactly
		// this, and an ingredient with no preferred vendor produces a line the purchase-order
		// generation then skips, so leaving them out would shrink the measured work silently.
		//
		// The recorded lead time cycles 0..5 days and is left NULL for one ingredient in seven, because
		// "no lead time recorded" is a distinct case the order-by arithmetic handles differently and a
		// fixture where every vendor has one would never exercise it.
		try (PreparedStatement ps = connection.prepareStatement("""
				WITH numbered_ingredients AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn FROM ingredients
				),
				numbered_vendors AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM vendors
				)
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred, last_price, lead_time_days)
				SELECT ?::uuid, v.id, i.id, true, 40 + (i.rn % 11) * 5,
				       CASE WHEN i.rn % 7 = 0 THEN NULL ELSE (i.rn % 6) END
				FROM numbered_ingredients i
				JOIN numbered_vendors v ON v.rn = (i.rn % v.total)
				""")) {
			ps.setString(1, tenantId.toString());
			ps.executeUpdate();
		}
	}

	private void plan(Connection connection, UUID tenantId, UUID actorUserId, TempleScale scale)
			throws SQLException {
		dailyMeals(connection, tenantId, actorUserId, scale, scale.historyDays(), true);
		dailyMeals(connection, tenantId, actorUserId, scale, scale.forwardDays(), false);
		events(connection, tenantId, actorUserId, scale, scale.expectedEventsPast(), true);
		events(connection, tenantId, actorUserId, scale, scale.expectedEventsFuture(), false);
	}

	/**
	 * Three meals a day, several dishes in each. Past days are COOKED and future days are PLANNED,
	 * which is the distinction {@code earliestDemandByIngredient()} filters on — so the historical
	 * rows are not padding, they are the rows the query has to decline.
	 */
	private void dailyMeals(Connection connection, UUID tenantId, UUID actorUserId, TempleScale scale,
			int days, boolean past) throws SQLException {
		if (days <= 0) {
			return;
		}
		String sql = """
				WITH numbered_recipes AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM recipes
				)
				INSERT INTO meal_plans
				    (tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield, day_type, status, created_by)
				SELECT ?::uuid,
				       CURRENT_DATE %s d.n,
				       (ARRAY['Breakfast', 'Lunch', 'Dinner'])[1 + (s.k %% 3)],
				       (ARRAY[TIME '08:00', TIME '12:00', TIME '19:30'])[1 + (s.k %% 3)],
				       r.id,
				       120 + (s.k %% 4) * 60,
				       CASE WHEN extract(isodow FROM (CURRENT_DATE %s d.n)) >= 6 THEN 'WEEKEND' ELSE 'REGULAR' END,
				       '%s',
				       ?::uuid
				FROM generate_series(1, ?) AS d(n)
				CROSS JOIN generate_series(0, ? - 1) AS s(k)
				JOIN numbered_recipes r ON r.rn = ((d.n::bigint * 5 + s.k) %% r.total)
				"""
				.formatted(past ? "-" : "+", past ? "-" : "+", past ? "COOKED" : "PLANNED");
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, tenantId.toString());
			ps.setString(2, actorUserId.toString());
			ps.setInt(3, days);
			ps.setInt(4, scale.dishesPerDay());
			ps.executeUpdate();
		}
	}

	/**
	 * Named events on top of the three daily meals — the shape E4-S15 gave this product, and the one
	 * the temple's own workbook expresses as a suffix on a duplicated recipe ("Varai Halva For
	 * Janmastami"). They cook a larger quantity, which is why they are worth generating separately
	 * rather than as another regular meal: a festival's claim is what pulls the ordering horizon out
	 * to thirty days.
	 */
	private void events(Connection connection, UUID tenantId, UUID actorUserId, TempleScale scale,
			int count, boolean past) throws SQLException {
		if (count <= 0) {
			return;
		}
		String sql = """
				WITH numbered_recipes AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM recipes
				)
				INSERT INTO meal_plans
				    (tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield, day_type, status,
				     event_name, created_by)
				SELECT ?::uuid,
				       CURRENT_DATE %s (g.n * ?),
				       'Event',
				       TIME '11:30',
				       r.id,
				       400,
				       'FESTIVAL',
				       '%s',
				       'Perf Event ' || lpad(g.n::text, 4, '0'),
				       ?::uuid
				FROM generate_series(1, ?) AS g(n)
				JOIN numbered_recipes r ON r.rn = (g.n %% r.total)
				"""
				.formatted(past ? "-" : "+", past ? "COOKED" : "PLANNED");
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.eventEveryDays());
			ps.setString(3, actorUserId.toString());
			ps.setInt(4, count);
			ps.executeUpdate();
		}
	}

	/**
	 * The table that only ever grows.
	 *
	 * <p>One opening receipt per ingredient, then {@code movementsPerDay} rows for every day of
	 * history: eight consumptions for each receipt, and an occasional adjustment. The quantities are
	 * chosen so that a nine-row cycle nets to roughly zero — a receipt of 6 KG against eight draws of
	 * 0.75 — which keeps on-hand hovering near its opening level for the whole five years instead of
	 * drifting to a number no temple would ever hold. That matters for the measurement: if stock
	 * drifted upwards the shopping list would come back empty, and if it drifted downwards every
	 * ingredient in the catalogue would be short. Neither is the case being measured.
	 *
	 * <p>All three movement types are real kinds from the ledger's own constraint. The mix is
	 * deliberate rather than uniform, because {@code to_on_hand_qty()} branches on the type and a
	 * single-type fixture would measure a function that never takes its other branch.
	 */
	private void ledger(Connection connection, UUID tenantId, UUID actorUserId, TempleScale scale)
			throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("""
				INSERT INTO stock_movements
				    (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id, created_at)
				SELECT ?::uuid, i.id, gen_random_uuid(), 120.000, 'KG', 'PO_RECEIPT', ?::uuid,
				       now() - make_interval(days => ? + 1)
				FROM ingredients i
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setString(2, actorUserId.toString());
			ps.setInt(3, scale.historyDays());
			ps.executeUpdate();
		}

		if (scale.historyDays() <= 0 || scale.movementsPerDay() <= 0) {
			return;
		}
		try (PreparedStatement ps = connection.prepareStatement("""
				WITH numbered_ingredients AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM ingredients
				),
				slots AS (
				    SELECT d.n AS day_back, m.seq
				    FROM generate_series(1, ?) AS d(n)
				    CROSS JOIN generate_series(1, ?) AS m(seq)
				)
				INSERT INTO stock_movements
				    (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id,
				     reason_category, created_at)
				SELECT ?::uuid,
				       i.id,
				       gen_random_uuid(),
				       CASE WHEN s.seq % 9 = 0 THEN 6.000
				            WHEN s.seq % 37 = 0 THEN 0.500
				            ELSE -0.750 END,
				       'KG',
				       CASE WHEN s.seq % 9 = 0 THEN 'PO_RECEIPT'
				            WHEN s.seq % 37 = 0 THEN 'ADJUSTMENT'
				            ELSE 'CONSUMPTION' END,
				       ?::uuid,
				       CASE WHEN s.seq % 37 = 0 THEN 'COUNT_CORRECTION' ELSE NULL END,
				       now() - make_interval(days => s.day_back)
				FROM slots s
				JOIN numbered_ingredients i ON i.rn = ((s.day_back::bigint * 11 + s.seq) % i.total)
				""")) {
			ps.setInt(1, scale.historyDays());
			ps.setInt(2, scale.movementsPerDay());
			ps.setString(3, tenantId.toString());
			ps.setString(4, actorUserId.toString());
			ps.executeUpdate();
		}
	}

	/**
	 * Purchase orders across the same history, because the third stream the derived list merges reads
	 * them. Most are RECEIVED — history — and one in ten is still SENT, which is what leaves an
	 * outstanding quantity for the list to subtract. No goods receipts are generated against them, so
	 * a SENT order is outstanding in full: the largest honest value, and the one that keeps the third
	 * stream non-empty.
	 */
	private void purchaseOrders(Connection connection, UUID tenantId, UUID actorUserId, TempleScale scale)
			throws SQLException {
		if (scale.purchaseOrders() <= 0) {
			return;
		}
		try (PreparedStatement ps = connection.prepareStatement("""
				WITH numbered_vendors AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM vendors
				)
				INSERT INTO purchase_orders
				    (tenant_id, po_number, vendor_id, status, order_date, needed_by, created_by)
				SELECT ?::uuid,
				       'PERF-PO-' || lpad(g.n::text, 6, '0'),
				       v.id,
				       CASE WHEN g.n % 10 = 0 THEN 'SENT' ELSE 'RECEIVED' END,
				       CURRENT_DATE - (g.n * 7),
				       CURRENT_DATE - (g.n * 7) + 3,
				       ?::uuid
				FROM generate_series(1, ?) AS g(n)
				JOIN numbered_vendors v ON v.rn = (g.n % v.total)
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setString(2, actorUserId.toString());
			ps.setInt(3, scale.purchaseOrders());
			ps.executeUpdate();
		}

		try (PreparedStatement ps = connection.prepareStatement("""
				WITH numbered_orders AS (
				    SELECT id, (row_number() OVER (ORDER BY po_number)) - 1 AS rn FROM purchase_orders
				),
				numbered_ingredients AS (
				    SELECT id, (row_number() OVER (ORDER BY name)) - 1 AS rn, count(*) OVER () AS total
				    FROM ingredients
				)
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				SELECT ?::uuid, o.id, i.id, 25.000, 'KG'
				FROM numbered_orders o
				CROSS JOIN generate_series(0, ? - 1) AS s(k)
				JOIN numbered_ingredients i ON i.rn = ((o.rn * 17 + s.k * 3) % i.total)
				""")) {
			ps.setString(1, tenantId.toString());
			ps.setInt(2, scale.linesPerPurchaseOrder());
			ps.executeUpdate();
		}
	}

	// --- counting -------------------------------------------------------------------------------

	private Counts count(Connection connection, Duration buildTime) throws SQLException {
		return new Counts(
				scalar(connection, "SELECT count(*) FROM ingredients"),
				scalar(connection, "SELECT count(*) FROM inventory_items"),
				scalar(connection, "SELECT count(*) FROM recipes"),
				scalar(connection, "SELECT count(*) FROM recipe_ingredients"),
				scalar(connection, "SELECT count(*) FROM meal_plans WHERE status = 'COOKED'"),
				scalar(connection, "SELECT count(*) FROM meal_plans WHERE status = 'PLANNED'"),
				scalar(connection, "SELECT count(*) FROM stock_movements"),
				scalar(connection, "SELECT count(*) FROM vendors"),
				scalar(connection, "SELECT count(*) FROM vendor_supplies"),
				scalar(connection, "SELECT count(*) FROM purchase_orders"),
				scalar(connection, "SELECT count(*) FROM purchase_order_lines"),
				buildTime);
	}

	private int scalar(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}
}
