package org.iskcon.kms.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.inventory.CommittedStockService;
import org.iskcon.kms.shoppinglist.ShoppingListService;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-141 — the shopping list may walk the plan once, and read the recipes it needs in one batch.
 *
 * <h2>Why this runs in the ordinary suite when its two siblings do not</h2>
 *
 * <p>{@link ShoppingListPerformanceIT} measures the same thing at a far more honest scale — five
 * years of ledger, a real HTTP call, timings — and is gated behind {@code KMS_PERF} because building
 * that fixture takes minutes. That makes it the right place to <em>read</em> the number and the wrong
 * place to <em>guard</em> it: a count nothing runs is a count that goes back up unnoticed, which is
 * precisely what happened to the two things this task fixed. T-140's ledger-read assertion sits in
 * that gated class today and has never once run in CI.
 *
 * <p>So this class asserts the same two facts over a thirty-day fixture that builds in seconds. The
 * statement counts do not depend on how much history the temple has — that is the whole point of
 * counting statements rather than timing them — so a small temple proves the same shape as a large
 * one. The numbers below were confirmed against the large fixture as well, and both are recorded in
 * {@code docs/work/proof/T-141.md}.
 *
 * <h2>What each assertion is actually saying</h2>
 *
 * <ul>
 *   <li><strong>The plan is walked once.</strong> {@code CommittedStockService.plannedDishes} is the
 *       one statement at the head of a walk, so counting it counts walks. Before T-141 a page load
 *       sent it twice: once for the sufficiency shortfall and once for the low-stock read, which are
 *       two projections of one answer.
 *   <li><strong>Every recipe the buying window needs is read in one batch.</strong> Before T-141
 *       {@code RecipeService} was asked for one recipe at a time, twice per distinct recipe-and-yield
 *       — 348 statements out of 392 for one page load at staging scale.
 *   <li><strong>Nothing is held past the transaction, and nothing is held across temples.</strong>
 *       The saving comes from a memo, and a memo in a multi-tenant application is a correctness
 *       question before it is a performance one. Two tests below hold it to its stated lifetime
 *       rather than taking the comment's word for it.
 * </ul>
 */
// Deliberately NOT tagged `perf`, unlike its two siblings in this package. That tag exists so a
// future build script can exclude the whole package, and a guard that a build change can silently
// switch off is not a guard. It lives here because StatementRecorder and TempleScaleFixture do.
@Import(PerfStubVerifierConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShoppingListStatementCountIT extends AbstractIntegrationTest {

	/**
	 * A month of history, three weeks ahead, eight recipes. Small enough to build in seconds and
	 * still big enough for the defect to be unmissable: nine dishes a day across the fourteen-day
	 * buying window is over a hundred dishes, and the fixture plans them at four different yields, so
	 * the old one-recipe-at-a-time read sent dozens of statements where two now do.
	 */
	private static final TempleScale SMALL =
			new TempleScale(40, 30, 8, 5, 9, 14, 30, 21, 80, 3, 12, 4);

	/** One batch is the head row and its ingredient lines: two statements, however many recipes. */
	private static final int STATEMENTS_PER_RECIPE_BATCH = 2;

	@Autowired
	private ShoppingListService shoppingListService;

	@Autowired
	private CommittedStockService committedStockService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;

	@BeforeAll
	void buildTheTemple() {
		admin = new JdbcTemplate(adminDataSource());

		// Tenants and users are scaffolding and go in as the superuser, exactly as every other
		// integration class here does; everything the fixture writes goes in as kms_app under RLS.
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('t141-temple', 'T-141 Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		UUID staff = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 't141-staff', 'T-141 Staff', 't141-staff@example.com', '+919876500941',
						'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);

		// A second temple with nothing in it at all. It exists for one assertion — that a memo
		// computed for the temple above is never handed to it — and an empty temple makes that
		// assertion unambiguous: anything it sees, it did not put there.
		otherTenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('t141-other', 'T-141 Other Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);

		TempleScaleFixture fixture =
				new TempleScaleFixture(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
		TempleScaleFixture.Counts counts = fixture.generate(tenant, staff, SMALL);

		// The same guard ShoppingListPerformanceIT uses, and for the same reason: a generator that
		// quietly wrote a handful of rows would make every count below small, believable and
		// meaningless.
		TempleScaleFixture.verify(counts, SMALL);
	}

	@AfterAll
	void removeTheTemples() {
		for (UUID id : List.of(tenant, otherTenant)) {
			if (id == null) {
				continue;
			}
			// delete_tenant_cascade rather than hand-written DELETEs: stock_movements is append-only
			// and this is the one path that announces itself to the trigger guarding it. query(), not
			// update(), because PostgreSQL returns a row for a void function.
			admin.query("SELECT delete_tenant_cascade(?)", rs -> { }, id);
			admin.update("DELETE FROM tenants WHERE id = ?", id);
		}
	}

	@Test
	@DisplayName("one shopping-list read walks the plan once and reads every recipe in one batch")
	void onePageLoadWalksThePlanOnce() {
		List<StatementRecorder.Executed> statements = recordAsTenant(tenant, shoppingListService::list);

		long walks = sentBy(statements, "inventory.CommittedStockService.plannedDishes");
		long recipeReads = sentBy(statements, "recipe.RecipeService");

		// Softly, so that both counts are reported. These are two independent defects that happened to
		// be fixed together, and a hard assertion on the first would hide the second from anybody
		// reading a failure — including whoever reverts one of them by accident.
		assertSoftly(softly -> {
			softly.assertThat(walks)
					.as("statements that begin a walk of the plan, for one read of the shopping list —"
							+ " the sufficiency shortfall and the low-stock read are two projections of one"
							+ " answer and must not each compute it (T-141)")
					.isEqualTo(1);

			softly.assertThat(recipeReads)
					.as("statements RecipeService sent for one read of the shopping list — every recipe the"
							+ " buying window needs is fetched in one batch, not one recipe at a time (T-141)")
					.isEqualTo(STATEMENTS_PER_RECIPE_BATCH);
		});
	}

	@Test
	@DisplayName("the memo does not outlive its transaction: a second read walks the plan again")
	void theMemoDiesWithTheTransaction() {
		recordAsTenant(tenant, shoppingListService::list);
		List<StatementRecorder.Executed> second = recordAsTenant(tenant, shoppingListService::list);

		// Not a performance assertion — a lifetime one. If the held claims survived the first
		// transaction, the second read would find them already there and send nothing at all, and a
		// list held across reads is exactly the thing T-140 refused to build.
		assertThat(sentBy(second, "inventory.CommittedStockService.plannedDishes"))
				.as("the second read must compute its own claims: the memo lives inside one"
						+ " transaction and is discarded when that transaction completes (T-141)")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a memo computed for one temple is never handed to another")
	void oneTemplesClaimsAreInvisibleToAnother() {
		Map<UUID, BigDecimal> committed = asTenant(
				tenant, () -> committedStockService.committedBaseByIngredient());
		assertThat(committed)
				.as("the fixture plans meals inside the buying window, so this temple claims stock")
				.isNotEmpty();

		// The same thread, immediately afterwards, as a temple that has nothing. A memo bound to the
		// thread rather than to the transaction — or one that did not name the temple it was computed
		// for — would hand this temple the map above.
		Map<UUID, BigDecimal> otherCommitted = asTenant(
				otherTenant, () -> committedStockService.committedBaseByIngredient());
		assertThat(otherCommitted)
				.as("a temple with no meal plans claims nothing, whatever the temple read before it did")
				.isEmpty();
	}

	// ---------------------------------------------------------------------------------------------

	private List<StatementRecorder.Executed> recordAsTenant(UUID tenantId, Runnable work) {
		return asTenant(tenantId, () -> {
			StatementRecorder.start();
			work.run();
			return StatementRecorder.stop();
		});
	}

	/**
	 * Runs on this thread with the tenant applied, and clears it afterwards whatever happens —
	 * threads are pooled and a leaked tenant is the one bug this whole suite exists to make
	 * impossible.
	 */
	private <T> T asTenant(UUID tenantId, java.util.function.Supplier<T> work) {
		TenantContext.set(tenantId);
		try {
			return work.get();
		} finally {
			TenantContext.clear();
		}
	}

	/** How many recorded statements were sent from inside this class or method. */
	private static long sentBy(List<StatementRecorder.Executed> statements, String callerPrefix) {
		return statements.stream().filter(s -> s.caller().startsWith(callerPrefix)).count();
	}
}
