package org.iskcon.kms.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The negative control for T-139: <strong>proof that the fixture actually produces the data it
 * claims to.</strong>
 *
 * <p>A behaviour task's control removes the fix and watches the tests fail. That shape does not
 * apply here, because nothing is being fixed. The honest control for a measurement harness is a
 * different question, and it is the one that would sink this task if it went unasked: <em>a
 * generator that silently wrote a hundred rows instead of a hundred and forty-six thousand would
 * produce a fast, plausible, completely meaningless number, and would pass every other check in the
 * harness.</em> So:
 *
 * <ol>
 *   <li>{@link #producesExactlyWhatItClaims()} — every table counted with {@code count(*)} and
 *       compared against the arithmetic in {@link TempleScale}, which is written beside the
 *       parameters rather than inside the generator, so the generator cannot agree with itself.
 *   <li>{@link #turningTheSizeUpTurnsTheDataUp()} — the size parameter is doubled and the ledger
 *       doubles with it. A generator that ignored its parameters passes test 1 at one scale and
 *       fails here.
 *   <li>{@link #theCountCheckHasTeeth()} — rows are deleted behind the check's back and the check is
 *       shown to <em>fail</em>. Without this, "the counts matched" is a sentence with no evidence
 *       that anything was capable of not matching.
 *   <li>{@link #oneTemplesDataIsInvisibleToAnother()} — two tenants generated side by side, each
 *       counted through the unprivileged application role. CLAUDE.md's first trap is that a fixture
 *       seeded as a superuser proves nothing about the query the application runs.
 * </ol>
 *
 * <p>Runs only under {@code KMS_PERF}, like its sibling:
 * {@code KMS_PERF=1 ./gradlew --no-daemon test --tests '*TempleScaleFixtureControlIT*'}
 */
@Tag("perf")
@EnabledIfEnvironmentVariable(named = "KMS_PERF", matches = "1|true|yes|on",
		disabledReason = "T-139's control builds real fixtures. Set KMS_PERF=1 to run it.")
@Import(PerfStubVerifierConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TempleScaleFixtureControlIT extends AbstractIntegrationTest {

	/**
	 * Small on purpose. The control is about whether the generator obeys its parameters, and that is
	 * as true at thirty days as at five years — while thirty days keeps the control cheap enough that
	 * somebody will actually run it before believing a timing.
	 */
	private static final TempleScale SMALL =
			new TempleScale(40, 30, 8, 5, 9, 14, 30, 21, 80, 3, 12, 4);

	private final PerfReport report = new PerfReport("T-139-control.txt");
	private final List<UUID> tenants = new ArrayList<>();

	private JdbcTemplate admin;
	private TempleScaleFixture fixture;

	// Phone numbers are generated from this rather than from a hash of the slug: two slugs that
	// collided would breach a uniqueness constraint and the failure would read as a fixture bug.
	private int templeCounter;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		fixture = new TempleScaleFixture(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
	}

	@AfterEach
	void tearDown() {
		for (UUID tenant : tenants) {
			admin.query("SELECT delete_tenant_cascade(?)", rs -> { }, tenant);
			admin.update("DELETE FROM tenants WHERE id = ?", tenant);
		}
		tenants.clear();
	}

	/**
	 * One file for the whole control, written once. Per-test writes would leave the file holding
	 * whichever test happened to run last, which is exactly the sort of quietly-partial artefact this
	 * control exists to rule out.
	 */
	@AfterAll
	void writeTheControlLog() {
		report.write();
	}

	@Test
	@DisplayName("the fixture produces exactly the rows it claims, counted from the database")
	void producesExactlyWhatItClaims() {
		UUID tenant = newTenant("control-a");
		TempleScaleFixture.Counts counts = fixture.generate(tenant, actorOf(tenant), SMALL);

		report.heading("Control 1 — what was asked for, and what was counted");
		report.say("  Scale: " + SMALL);
		report.blank();
		line("ingredients", counts.ingredients(), SMALL.ingredients());
		line("inventory_items", counts.inventoryItems(), SMALL.inventoryItems());
		line("recipes", counts.recipes(), SMALL.recipes());
		line("recipe_ingredients", counts.recipeIngredients(), SMALL.expectedRecipeIngredients());
		line("meal_plans COOKED", counts.mealPlansCooked(), SMALL.expectedCookedPlans());
		line("meal_plans PLANNED", counts.mealPlansPlanned(), SMALL.expectedPlannedPlans());
		line("stock_movements", counts.stockMovements(), SMALL.expectedStockMovements());
		line("vendors", counts.vendors(), SMALL.vendors());
		line("vendor_supplies", counts.vendorSupplies(), SMALL.expectedVendorSupplies());
		line("purchase_orders", counts.purchaseOrders(), SMALL.purchaseOrders());
		line("purchase_order_lines", counts.purchaseOrderLines(), SMALL.expectedPurchaseOrderLines());

		TempleScaleFixture.verify(counts, SMALL);
		assertThat(counts.stockMovements()).isEqualTo(SMALL.expectedStockMovements());
		assertThat(counts.mealPlans()).isEqualTo(SMALL.expectedMealPlans());
	}

	@Test
	@DisplayName("doubling the history parameter doubles the ledger it generates")
	void turningTheSizeUpTurnsTheDataUp() {
		UUID small = newTenant("control-small");
		TempleScaleFixture.Counts atThirty = fixture.generate(small, actorOf(small), SMALL);

		TempleScale twice = SMALL.withHistoryDays(SMALL.historyDays() * 2);
		UUID large = newTenant("control-large");
		TempleScaleFixture.Counts atSixty = fixture.generate(large, actorOf(large), twice);

		report.heading("Control 2 — turn the size parameter up, and the data goes up with it");
		report.say("  %d days of history: %s stock movements, %s cooked meal plans"
				.formatted(SMALL.historyDays(), PerfReport.grouped(atThirty.stockMovements()),
						PerfReport.grouped(atThirty.mealPlansCooked())));
		report.say("  %d days of history: %s stock movements, %s cooked meal plans"
				.formatted(twice.historyDays(), PerfReport.grouped(atSixty.stockMovements()),
						PerfReport.grouped(atSixty.mealPlansCooked())));
		report.blank();
		report.say("  A generator that ignored its parameters would print the same two lines twice.");

		TempleScaleFixture.verify(atThirty, SMALL);
		TempleScaleFixture.verify(atSixty, twice);

		// The ledger's opening receipts do not scale with history — one per ingredient, whatever the
		// history — so the daily churn is what doubles, not the total. Stating that rather than
		// asserting a round 2× that would quietly be wrong.
		int churnAtThirty = atThirty.stockMovements() - SMALL.ingredients();
		int churnAtSixty = atSixty.stockMovements() - SMALL.ingredients();
		assertThat(churnAtSixty).isEqualTo(churnAtThirty * 2);
		assertThat(atSixty.mealPlansCooked()).isGreaterThan(atThirty.mealPlansCooked());
	}

	@Test
	@DisplayName("the count check fails when the rows are not there — it is not a formality")
	void theCountCheckHasTeeth() {
		UUID tenant = newTenant("control-teeth");
		TempleScaleFixture.Counts honest = fixture.generate(tenant, actorOf(tenant), SMALL);
		TempleScaleFixture.verify(honest, SMALL);

		// Delete rows behind the check's back, as the superuser — which is also the only role that
		// can, because stock_movements is append-only to the application. This is the simulation of a
		// generator that under-produced: same code path, same verify() call, fewer rows.
		int removed = admin.update("""
				DELETE FROM stock_movements
				WHERE id IN (SELECT id FROM stock_movements WHERE tenant_id = ? LIMIT 1000)
				""", tenant);

		TempleScaleFixture.Counts crippled = fixture.recount(tenant);

		report.heading("Control 3 — the check, shown failing");
		report.say("  Deleted " + removed + " stock_movements rows behind the check's back.");
		report.say("  Counted before: " + honest.stockMovements() + "   after: " + crippled.stockMovements());

		assertThatThrownBy(() -> TempleScaleFixture.verify(crippled, SMALL))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("The fixture did not produce what it claimed")
				.hasMessageContaining("stock_movements: counted " + crippled.stockMovements())
				.hasMessageContaining("expected " + SMALL.expectedStockMovements())
				.hasMessageContaining("short by " + removed);

		try {
			TempleScaleFixture.verify(crippled, SMALL);
		} catch (IllegalStateException e) {
			for (String line : e.getMessage().lines().toList()) {
				report.say("    " + line);
			}
		}
		report.blank();
		report.say("  That refusal is what stops a fast, meaningless number reaching the proof file.");
	}

	@Test
	@DisplayName("each temple's generated data is invisible to the other, as the application role")
	void oneTemplesDataIsInvisibleToAnother() {
		UUID first = newTenant("control-rls-a");
		UUID second = newTenant("control-rls-b");

		TempleScale tiny = SMALL.withHistoryDays(5);
		fixture.generate(first, actorOf(first), SMALL);
		fixture.generate(second, actorOf(second), tiny);

		TempleScaleFixture.Counts asFirst = fixture.recount(first);
		TempleScaleFixture.Counts asSecond = fixture.recount(second);

		report.heading("Control 4 — Row-Level Security, from the application role's side");
		report.say("  Temple A asked for %d days of history and counts %s stock movements."
				.formatted(SMALL.historyDays(), PerfReport.grouped(asFirst.stockMovements())));
		report.say("  Temple B asked for %d days of history and counts %s stock movements."
				.formatted(tiny.historyDays(), PerfReport.grouped(asSecond.stockMovements())));
		report.blank();
		report.say("  Both counts were taken as " + APP_ROLE + " — no DDL, no BYPASSRLS — with");
		report.say("  app.tenant_id set to that temple. Neither can see the other's rows, so the");
		report.say("  timings are taken against the plan the running application actually gets.");

		// Each sees exactly its own and nothing of the other's. If RLS were absent, or the fixture had
		// been seeded as the superuser, both of these would report the sum of the two temples.
		TempleScaleFixture.verify(asFirst, SMALL);
		TempleScaleFixture.verify(asSecond, tiny);
		assertThat(asFirst.stockMovements()).isEqualTo(SMALL.expectedStockMovements());
		assertThat(asSecond.stockMovements()).isEqualTo(tiny.expectedStockMovements());
		assertThat(asFirst.stockMovements()).isNotEqualTo(asSecond.stockMovements());
	}

	// -------------------------------------------------------------------------------------------

	private void line(String table, int counted, int expected) {
		report.say("  %-24s counted %8s   expected %8s   %s".formatted(
				table, PerfReport.grouped(counted), PerfReport.grouped(expected),
				counted == expected ? "ok" : "MISMATCH"));
	}

	private UUID newTenant(String slug) {
		UUID tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, "Control Temple " + slug);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Control Staff', ?, ?, 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant, "uid-" + slug, slug + "@example.com",
				"+9198765" + String.format("%05d", ++templeCounter));
		tenants.add(tenant);
		return tenant;
	}

	private UUID actorOf(UUID tenant) {
		return admin.queryForObject("SELECT id FROM users WHERE tenant_id = ? LIMIT 1", UUID.class, tenant);
	}
}
