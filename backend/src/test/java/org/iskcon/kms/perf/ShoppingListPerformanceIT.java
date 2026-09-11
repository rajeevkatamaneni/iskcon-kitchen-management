package org.iskcon.kms.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.perf.PerfReport.Timing;
import org.iskcon.kms.perf.PerfStubVerifierConfiguration.PerfStubTokenVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-139 — how long the shopping list takes on a temple with years of history behind it.
 *
 * <p><strong>THIS DOES NOT RUN IN THE DEFAULT SUITE, AND MUST NOT.</strong> Building the fixture
 * takes minutes. To run it:
 *
 * <pre>{@code
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
 * KMS_PERF=1 ./gradlew --no-daemon test --tests '*ShoppingListPerformanceIT*'
 * }</pre>
 *
 * <p>Without {@code KMS_PERF} the class is disabled and {@code ./gradlew test} is unaffected. The
 * gate is an <em>environment variable</em> rather than a {@code -D} system property because
 * {@code backend/build.gradle.kts} does not forward system properties to the test worker and this
 * task is not permitted to edit it — but a Gradle worker inherits its parent's environment. The
 * {@code perf} tag is there beside it so a future build script can exclude the whole package by tag
 * if it would rather.
 *
 * <p><strong>Measured at the API, not at the method.</strong> The two reads worth measuring —
 * {@code earliestDemandByIngredient()} and {@code onHandBaseByIngredient()} — are private, and T-132
 * is rewriting both of them as this is written. A harness bound to them would be invalidated by the
 * rewrite it exists to judge. {@code GET /api/v1/shopping-list} exists before and after, so timing
 * that measures the change instead of dying of it.
 *
 * <p><strong>Two timings, because D-24 moves work from one to the other.</strong> Today the
 * derivation happens on {@code POST /shopping-list/regenerate} — a button press, and a nightly job —
 * and {@code GET} is a single indexed read of a stored table. After T-132 the stored table is gone
 * and everything {@code regenerate} does today happens on every {@code GET}. So today's
 * {@code regenerate} figure is the honest forecast of tomorrow's page load, and today's {@code GET}
 * figure is the baseline it will be compared against. Reporting only one of them would say nothing
 * about the change.
 *
 * <p><strong>Nothing here asserts a duration.</strong> The only assertions are that the fixture
 * really contains what it claims and that the endpoints answered 200 — see
 * {@link TempleScaleFixture#verify}. A build that goes red on a millisecond count flakes on a loaded
 * machine and trains people to re-run rather than read. If a gate is wanted later it should compare
 * two runs of this harness rather than name an absolute ceiling.
 */
@Tag("perf")
@EnabledIfEnvironmentVariable(named = "KMS_PERF", matches = "1|true|yes|on",
		disabledReason = "T-139's fixture takes minutes to build. Set KMS_PERF=1 to run it.")
@Import(PerfStubVerifierConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShoppingListPerformanceIT extends AbstractIntegrationTest {

	/**
	 * {@code earliestDemandByIngredient()} as it stands on 2026-09-10, copied verbatim from
	 * {@code ShoppingListService}.
	 *
	 * <p>Copied rather than called because the method is private and this task may not touch that
	 * file. That makes it a snapshot with an expiry date, and it is labelled as one in the report: the
	 * {@code EXPLAIN} below is the <em>baseline</em> T-132's rewrite gets compared against, and
	 * whoever takes the after-reading has to re-take this text from the file first.
	 */
	private static final String EARLIEST_DEMAND_SQL = """
			SELECT ri.ingredient_id, MIN(mp.plan_date) AS earliest
			FROM meal_plans mp
			JOIN recipe_ingredients ri ON ri.recipe_id = mp.recipe_id
			WHERE mp.status = 'PLANNED' AND mp.plan_date >= CURRENT_DATE
			GROUP BY ri.ingredient_id
			""";

	/**
	 * {@code onHandBaseByIngredient()} as it stands on 2026-09-10, copied verbatim.
	 *
	 * <p>This is the one the brief singles out: it aggregates the whole of {@code stock_movements}
	 * with no date bound at all, on a table that only ever grows. Its cost is therefore the temple's
	 * entire operating history, re-paid on every call — and after T-132 every call is somebody opening
	 * a screen.
	 */
	private static final String ON_HAND_SQL = """
			SELECT ingredient_id,
			       SUM(to_on_hand_qty(quantity, unit, movement_type)) AS base
			FROM stock_movements GROUP BY ingredient_id
			""";

	private static final String STAFF_UID = "uid-perf-staff";

	/**
	 * How many page loads the {@code pg_stat_all_tables} window covers.
	 *
	 * <p>Twenty rather than one, because PostgreSQL's counters arrive late and by an amount that
	 * depends on how busy the backend has just been — see the note the report prints beside the
	 * table. A couple of loads in flight either side of a twenty-load window is a rounding error; the
	 * same two either side of a one-load window is the difference between one and three.
	 */
	private static final int STAT_WINDOW_LOADS = 20;

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private PerfStubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private TempleScaleFixture fixture;
	private TempleScale scale;
	private TempleScaleFixture.Counts counts;
	private UUID tenant;
	private final PerfReport report = new PerfReport("T-139-shopping-list.txt");

	@BeforeAll
	void buildTheTemple() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		stubVerifier.accept(STAFF_UID);

		// The tenant row and its users are scaffolding, created as the superuser exactly as every
		// other integration class here does. A tenant is not tenant-owned data and the application
		// never creates one from inside a tenant context, so seeding it under RLS would be modelling
		// something that does not happen. EVERYTHING BELOW THIS POINT goes in as kms_app with
		// app.tenant_id set — see TempleScaleFixture's own header for why that is load-bearing.
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('perf-temple', 'Perf Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		UUID staff = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Perf Staff', 'perf-staff@example.com', '+919876500901', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant, STAFF_UID);

		scale = TempleScale.fromEnvironment();
		fixture = new TempleScaleFixture(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
		counts = fixture.generate(tenant, staff, scale);

		// The negative control, and the reason it is here rather than in a test method: if the
		// generator quietly wrote a hundred rows where it claimed a hundred and forty-six thousand,
		// every timing below would be fast, meaningless and completely believable. verify() compares
		// what the database actually holds against the arithmetic in TempleScale, and refuses the run
		// rather than reporting a number nobody could have checked.
		TempleScaleFixture.verify(counts, scale);
	}

	@AfterAll
	void removeTheTemple() {
		if (tenant == null) {
			return;
		}
		try {
			// The schema's own purge, rather than a hand-written DELETE cascade: stock_movements is
			// append-only, and delete_tenant_cascade is the one path that announces itself to the
			// trigger guarding it. Leaving 150,000 rows behind would poison every later test class in
			// this JVM, several of which delete whole tables in their teardown.
			// query(), not update(): PostgreSQL returns a row for a void function, and executeUpdate
			// refuses a statement that returns one.
			admin.query("SELECT delete_tenant_cascade(?)", rs -> { }, tenant);
			admin.update("DELETE FROM tenants WHERE id = ?", tenant);
		} catch (RuntimeException e) {
			// Reported, never swallowed and never allowed to mask the measurement: a failed cleanup is
			// a real finding about a fixture this size, and it is not a reason to lose the numbers.
			System.out.println("CLEANUP FAILED for tenant " + tenant + ": " + e);
		}
	}

	@Test
	@DisplayName("T-139: the shopping list, timed at the API over a temple with years of history")
	void measure() {
		report.say("T-139 — shopping list performance over a temple at realistic scale");
		report.say("Run at " + java.time.ZonedDateTime.now());

		reportTheFixture();

		int warmups = intFromEnvironment("KMS_PERF_WARMUPS", 2);
		int samples = intFromEnvironment("KMS_PERF_SAMPLES", 7);

		report.heading("What was measured, and how");
		report.say("  Warm-up calls (discarded):  " + warmups);
		report.say("  Measured calls:             " + samples);
		report.say("  Signed in as:               Kitchen Staff (MANAGE_PURCHASE_ORDERS)");
		report.say("  Transport:                  real HTTP to the embedded server, not MockMvc");

		// Which shape of the endpoint is in this tree? Probed, never assumed.
		//
		// Before T-132 the derivation happens on POST /regenerate and the GET is one indexed read of
		// shopping_list_lines. After it, the POST is gone and the GET does all of that work. Asking
		// the running application which of those it is costs one call and is the difference between a
		// harness that survives the rewrite and one the rewrite invalidates — which is the whole
		// reason this task measures at the API rather than at the two private methods.
		boolean derivedOnGet = !endpointExists(HttpMethod.POST, "/api/v1/shopping-list/regenerate");
		report.blank();
		report.say(derivedOnGet
				? "  POST /shopping-list/regenerate is NOT in this tree. T-132 has landed: the GET below"
						+ " IS the derivation, computed on every page load."
				: "  POST /shopping-list/regenerate is still in this tree. The GET below is the pre-T-132"
						+ " stored read, and the POST is the derivation D-24 moves onto it.");

		Timing regenerate = derivedOnGet ? null
				: time("POST /api/v1/shopping-list/regenerate", warmups, samples,
						() -> call(HttpMethod.POST, "/api/v1/shopping-list/regenerate"));

		Timing get = time("GET  /api/v1/shopping-list", warmups, samples,
				() -> call(HttpMethod.GET, "/api/v1/shopping-list"));

		ResponseEntity<String> list = call(HttpMethod.GET, "/api/v1/shopping-list");
		int linesOnTheList = countTopLevelObjects(list.getBody());

		report.heading("Timings");
		if (regenerate != null) {
			report.say("  " + regenerate.describe());
		}
		report.say("  " + get.describe());
		report.blank();
		report.say("  The list came back with " + linesOnTheList + " lines.");
		report.blank();
		if (derivedOnGet) {
			report.say("  This tree has T-132's rewrite in it, so the GET figure above is the derived list:");
			report.say("  the shortfall walk across the buying window, the low-stock read, the outstanding");
			report.say("  purchase-order quantities and the two reads named in T-139's brief, all of it on");
			report.say("  every page load, for every person who opens the screen.");
		} else {
			report.say("  Read these two together. On this tree the derivation happens on the POST — a button");
			report.say("  press and a nightly job — and the GET is one indexed read of shopping_list_lines.");
			report.say("  AFTER D-24/T-132 the stored table is gone, so what the POST costs here is what the");
			report.say("  GET will cost then, on every page load.");
		}

		long ledgerReads = reportWhichCallsReadTheLedger();
		reportHowOftenOnePageLoadReadsEachTable();

		reportExplain("earliestDemandByIngredient() — meal plans joined to recipe ingredients",
				EARLIEST_DEMAND_SQL);
		reportExplain("onHandBaseByIngredient() — the whole of stock_movements, no date bound",
				ON_HAND_SQL);

		report.heading("Fixture size, repeated so it is never separated from the numbers");
		report.say("  " + oneLineSize());
		report.blank();
		report.say("  Turn the size up with the environment, e.g. KMS_PERF_YEARS=10, or");
		report.say("  KMS_PERF_MOVEMENTS_PER_DAY=160. Every field of TempleScale has an override.");
		report.write();

		// The assertions, and none of them is a threshold.
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(counts.stockMovements()).isEqualTo(scale.expectedStockMovements());

		// T-140. A count, not a duration: the fix is that the ledger is summed once for a page load
		// instead of once per stream that wants it, and a count is the same number on a loaded
		// machine as on an idle one. It is asserted here rather than left to be read out of the
		// report because a number in a report is a number nobody notices going back up.
		assertThat(ledgerReads)
				.as("statements against stock_movements for one GET /api/v1/shopping-list")
				.isEqualTo(1);
	}

	// -------------------------------------------------------------------------------------------

	private void reportTheFixture() {
		report.heading("The fixture — every figure counted with count(*), not assumed");
		report.say("  Built in %s, as %s, under Row-Level Security"
				.formatted(PerfReport.humanise(counts.buildTime()), APP_ROLE));
		report.blank();
		report.say("  ingredients            %10s   (staging holds about 150)".formatted(PerfReport.grouped(counts.ingredients())));
		report.say("  inventory_items        %10s   (staging holds about 116)".formatted(PerfReport.grouped(counts.inventoryItems())));
		report.say("  recipes                %10s   (staging holds about 26)".formatted(PerfReport.grouped(counts.recipes())));
		report.say("  recipe_ingredients     %10s   (%d lines per recipe)".formatted(PerfReport.grouped(counts.recipeIngredients()), scale.ingredientsPerRecipe()));
		report.say("  meal_plans  COOKED     %10s   (%d dishes a day for %.1f years, plus events)".formatted(PerfReport.grouped(counts.mealPlansCooked()), scale.dishesPerDay(), scale.historyYears()));
		report.say("  meal_plans  PLANNED    %10s   (%d days ahead — the ordering horizon is 14, 30 for a festival)".formatted(PerfReport.grouped(counts.mealPlansPlanned()), scale.forwardDays()));
		report.say("  stock_movements        %10s   ← the table that only ever grows".formatted(PerfReport.grouped(counts.stockMovements())));
		report.say("  vendors                %10s".formatted(PerfReport.grouped(counts.vendors())));
		report.say("  vendor_supplies        %10s   (one preferred vendor per ingredient)".formatted(PerfReport.grouped(counts.vendorSupplies())));
		report.say("  purchase_orders        %10s   (one in ten still SENT, so outstanding in full)".formatted(PerfReport.grouped(counts.purchaseOrders())));
		report.say("  purchase_order_lines   %10s".formatted(PerfReport.grouped(counts.purchaseOrderLines())));
	}

	private String oneLineSize() {
		return "%s stock movements, %s meal plans (%s planned), %s ingredients, %s recipes, %.1f years of history"
				.formatted(PerfReport.grouped(counts.stockMovements()), PerfReport.grouped(counts.mealPlans()),
						PerfReport.grouped(counts.mealPlansPlanned()), PerfReport.grouped(counts.ingredients()),
						PerfReport.grouped(counts.recipes()), scale.historyYears());
	}

	/**
	 * How many times one page load reads each of the big tables, from {@code pg_stat_all_tables}.
	 *
	 * <p>Asked because the two other numbers do not add up on their own. If the page costs several
	 * hundred milliseconds and the aggregate the brief singled out costs a few dozen, the rest is
	 * somewhere — and the likeliest somewhere, on a screen that merges three streams, is the same
	 * unbounded aggregate being run more than once. This answers that without opening the service or
	 * naming a private method: it counts the reads the running application actually performs.
	 *
	 * <p>The sleep is not padding. PostgreSQL's backends accumulate these counters locally and flush
	 * them when a transaction ends, with the collector lagging by up to a second — reading
	 * immediately would report a delta of zero and be read as "it never touched the table", which is
	 * the most misleading answer available.
	 */
	/**
	 * Which application methods read each table during one page load, counted at the JDBC boundary.
	 *
	 * <p>T-140's first step, and the reason it is a second measurement rather than a re-reading of
	 * the one below: {@code pg_stat_all_tables} says <em>how many index scans happened</em>, which is
	 * a fact about query plans — a nested loop over 26 rows is 26 scans of one statement — and its
	 * counters arrive up to a second late, so a delta bracketing one request can carry the tail of
	 * the requests before it. Neither of those can be argued with from the report; both are removed
	 * by counting what the application asked for, at the moment it asked, with its own stack in view.
	 *
	 * @return how many statements this page load sent against {@code stock_movements}
	 */
	private long reportWhichCallsReadTheLedger() {
		StatementRecorder.start();
		require200(call(HttpMethod.GET, "/api/v1/shopping-list"), "GET for the statement recording");
		List<StatementRecorder.Executed> statements = StatementRecorder.stop();

		report.heading("What ONE page load actually asks the database for (recorded at the JDBC boundary)");
		report.say("  " + statements.size() + " statements in total, including the per-connection"
				+ " set_config calls the tenancy wrapper makes.");
		report.blank();
		report.say("  %-22s %12s".formatted("table", "statements"));
		for (String table : List.of("stock_movements", "meal_plans", "recipe_ingredients",
				"inventory_items", "purchase_order_lines", "ingredients", "vendor_supplies",
				"shopping_list_lines", "recipes")) {
			report.say("  %-22s %12d".formatted(table, StatementRecorder.countTouching(statements, table)));
		}

		long ledgerReads = StatementRecorder.countTouching(statements, "stock_movements");
		report.blank();
		report.say("  Every statement that named stock_movements, and the method that sent it:");
		report.blank();
		int n = 0;
		for (StatementRecorder.Executed s : statements) {
			if (!s.touches("stock_movements")) {
				continue;
			}
			report.say("  %d. %s".formatted(++n, s.oneLine()));
			for (String frame : s.callers()) {
				report.say("        " + frame);
			}
			report.blank();
		}

		report.say("  The ten methods that sent the most statements of any kind:");
		statements.stream()
				.collect(java.util.stream.Collectors.groupingBy(
						StatementRecorder.Executed::caller, java.util.stream.Collectors.counting()))
				.entrySet().stream()
				.sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(10)
				.forEach(e -> report.say("    %6d  %s".formatted(e.getValue(), e.getKey())));

		return ledgerReads;
	}

	private void reportHowOftenOnePageLoadReadsEachTable() {
		List<String> tables = List.of("stock_movements", "meal_plans", "recipe_ingredients",
				"inventory_items", "purchase_order_lines", "ingredients", "vendor_supplies");

		java.util.Map<String, long[]> before = statsOnceTheyStopMoving(tables);
		for (int i = 0; i < STAT_WINDOW_LOADS; i++) {
			require200(call(HttpMethod.GET, "/api/v1/shopping-list"), "GET for the read count");
		}
		java.util.Map<String, long[]> after = statsOnceTheyStopMoving(tables);

		report.heading("How often the database says it read each table (pg_stat_all_tables delta)");
		report.say("  Over " + STAT_WINDOW_LOADS + " page loads, not one, and the per-load figure is");
		report.say("  the division. Two things make a one-request delta here worth less than it looks,");
		report.say("  and between them they are how T-139 read three statements as nine scans:");
		report.blank();
		report.say("    · A backend flushes its counters when a transaction ends and at most about");
		report.say("      once a second, and it flushes when it is next used. So a snapshot taken");
		report.say("      after a burst of page loads can still have several of them in flight, and");
		report.say("      they land in the next window and read as that request's work. Spreading the");
		report.say("      window over " + STAT_WINDOW_LOADS + " loads makes that a rounding error instead of a multiplier.");
		report.say("    · A scan is a plan execution, not a statement: one query driving a nested loop");
		report.say("      over 26 rows registers 26 index scans. So this table answers 'how much work");
		report.say("      did the plans do', and the statement counts above answer 'how many times did");
		report.say("      the application ask'. T-140 is about the second question.");
		report.blank();
		report.say("  %-22s %10s %10s %14s %14s %12s".formatted(
				"table", "seq scans", "idx scans", "seq rows", "idx rows", "idx scans/load"));
		for (String table : tables) {
			long[] a = after.getOrDefault(table, new long[4]);
			long[] b = before.getOrDefault(table, new long[4]);
			report.say("  %-22s %10d %10d %14d %14d %12.1f".formatted(
					table, a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3],
					(a[1] - b[1]) / (double) STAT_WINDOW_LOADS));
		}

		// The legible form of the whole finding, and the one to quote: how many times a page load
		// reads the temple's entire ledger. It is a division of two measured numbers rather than a
		// third measurement — rows read, over the rows the fixture counted, over the loads in the
		// window — so it cannot drift away from either.
		long[] a = after.getOrDefault("stock_movements", new long[4]);
		long[] b = before.getOrDefault("stock_movements", new long[4]);
		double wholeLedgerReads =
				(a[3] - b[3]) / (double) counts.stockMovements() / STAT_WINDOW_LOADS;
		report.blank();
		report.say("  stock_movements holds %s rows, and %s page loads read %s of them."
				.formatted(PerfReport.grouped(counts.stockMovements()), STAT_WINDOW_LOADS,
						PerfReport.grouped(a[3] - b[3])));
		report.say("  That is %.2f readings of the whole ledger per page load.".formatted(wholeLedgerReads));
	}

	/**
	 * Reads {@code pg_stat_all_tables} until it stops changing, and returns the settled figures.
	 *
	 * <p>A fixed sleep is the wrong instrument here and T-139's proof already shows why: a backend
	 * flushes its counters at transaction end and no more than about once a second, so how long it
	 * takes for a request's reads to become visible depends on how busy that backend has just been.
	 * Sleeping too little reports a request's work in the <em>next</em> window; sleeping too much is a
	 * guess that happens to be long enough today. Waiting for two identical reads in a row asks the
	 * question the sleep was standing in for — <em>has everything landed?</em> — and answers it.
	 *
	 * <p>Twelve seconds is a ceiling, not a target; it settles in two or three reads in practice. If
	 * it ever does not, the report says so rather than quietly printing an unsettled number.
	 */
	private java.util.Map<String, long[]> statsOnceTheyStopMoving(List<String> tables) {
		java.util.Map<String, long[]> previous = null;
		for (int attempt = 0; attempt < 24; attempt++) {
			pause(500);
			java.util.Map<String, long[]> current = fixture.tableStats(tables);
			if (previous != null && sameCounters(previous, current, tables)) {
				return current;
			}
			previous = current;
		}
		report.say("  WARNING: pg_stat_all_tables never settled in 12 s — the delta below is not"
				+ " one request's and must not be quoted as one.");
		return previous;
	}

	private boolean sameCounters(
			java.util.Map<String, long[]> a, java.util.Map<String, long[]> b, List<String> tables) {
		for (String table : tables) {
			if (!java.util.Arrays.equals(
					a.getOrDefault(table, new long[4]), b.getOrDefault(table, new long[4]))) {
				return false;
			}
		}
		return true;
	}

	private void pause(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void reportExplain(String title, String sql) {
		report.heading("EXPLAIN (ANALYZE, BUFFERS) — " + title);
		report.say("  Run as " + APP_ROLE + " with app.tenant_id set, so the RLS qualification is in the");
		report.say("  plan exactly as it is for the running application. The SQL is a verbatim copy");
		report.say("  taken from ShoppingListService on 2026-09-10; re-take it before comparing.");
		report.blank();
		for (String line : sql.strip().lines().toList()) {
			report.say("    " + line);
		}
		report.blank();
		for (String line : fixture.explain(tenant, sql)) {
			report.say("    " + line);
		}
	}

	private Timing time(String label, int warmups, int samples, java.util.function.Supplier<ResponseEntity<String>> call) {
		for (int i = 0; i < warmups; i++) {
			require200(call.get(), label);
		}
		List<Long> nanos = new ArrayList<>();
		for (int i = 0; i < samples; i++) {
			long startedAt = System.nanoTime();
			ResponseEntity<String> response = call.get();
			nanos.add(System.nanoTime() - startedAt);
			require200(response, label);
		}
		return new Timing(label, nanos);
	}

	/**
	 * Whether the application still serves this endpoint at all.
	 *
	 * <p>404 and 405 both mean "not here": Spring answers 405 when a path exists under a different
	 * method, which is what {@code POST /shopping-list} (the hand-add) would otherwise make of a
	 * missing {@code /regenerate}. A 403 would mean the endpoint is there and the signed-in user may
	 * not use it, which is a different fact and must not be read as absence.
	 */
	private boolean endpointExists(HttpMethod method, String path) {
		HttpStatus status = HttpStatus.valueOf(call(method, path).getStatusCode().value());
		return status != HttpStatus.NOT_FOUND && status != HttpStatus.METHOD_NOT_ALLOWED;
	}

	private void require200(ResponseEntity<String> response, String label) {
		if (!response.getStatusCode().is2xxSuccessful()) {
			throw new IllegalStateException(
					label + " answered " + response.getStatusCode() + ": " + response.getBody());
		}
	}

	private ResponseEntity<String> call(HttpMethod method, String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(PerfStubTokenVerifier.TOKEN);
		return rest.exchange(path, method, new HttpEntity<>(headers), String.class);
	}

	/**
	 * How many lines came back, counted by brace depth rather than by parsing.
	 *
	 * <p>Deliberately not deserialised into the view type: this class is measuring an endpoint, and
	 * binding to the shape of its payload is exactly the coupling that would break when T-132 changes
	 * that shape. Depth-1 objects is a fact about JSON, not about this API.
	 */
	private int countTopLevelObjects(String json) {
		if (json == null) {
			return 0;
		}
		int depth = 0;
		int count = 0;
		boolean inString = false;
		boolean escaped = false;
		for (char c : json.toCharArray()) {
			if (escaped) {
				escaped = false;
				continue;
			}
			if (c == '\\') {
				escaped = true;
			} else if (c == '"') {
				inString = !inString;
			} else if (!inString && c == '{') {
				if (depth == 1) {
					count++;
				}
				depth++;
			} else if (!inString && c == '}') {
				depth--;
			} else if (!inString && c == '[') {
				depth++;
			} else if (!inString && c == ']') {
				depth--;
			}
		}
		return count;
	}

	private static int intFromEnvironment(String name, int fallback) {
		String raw = System.getenv(name);
		return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
	}
}
