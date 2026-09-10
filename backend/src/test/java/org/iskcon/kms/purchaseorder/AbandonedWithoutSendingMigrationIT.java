package org.iskcon.kms.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V120 — the one-off correction of orders that claimed a vendor no-show without ever being sent
 * (T-129).
 *
 * <p><b>Why this needs a test of its own, rather than a line in PurchaseOrderIT.</b> The suite
 * migrates a database and then puts rows into it, so by the time any ordinary integration test can
 * write a row, V120 has already run. The rows this migration exists for are the ones that were
 * there <em>before</em> it — on staging, PO-2026-0036, which the coordinator raised as a draft,
 * never sent, and cancelled with "Vendor Never Delivered this Order" ticked, giving Heritage Fresh
 * Dairy 0% for an order they had never heard of. There is no way to create that row after the
 * ruling: {@code PurchaseOrderService.cancel} refuses it outright now. So the only honest test is
 * one that stops the migration history short, seeds the bad row at the schema version it could
 * legitimately have existed at, and then lets the rest run.
 *
 * <p><b>And the failure it is really guarding against is silence.</b> {@code purchase_orders}
 * carries {@code enable_tenant_rls()} and the migration role is unprivileged, so a plain
 * cross-tenant UPDATE inside a migration matches nothing at all — the policy fails closed through
 * {@code NULLIF} rather than raising. A V120 written without its per-tenant loop would migrate
 * cleanly, report success, and leave every wrong row exactly as it found it. That is the shape that
 * has already cost this project three hotfix migrations, and no test that merely runs the
 * migrations on an empty database can see it.
 *
 * <p>{@link org.iskcon.kms.TenantLoopMigrationIT} is the neighbouring guard and answers a different
 * question: that each loop body is <em>planned</em> against a database with a temple in it. This one
 * asks whether the loop body does its work.
 */
class AbandonedWithoutSendingMigrationIT extends AbstractIntegrationTest {

	/**
	 * The last version at which an unsent order could carry the flag. V120 is the correction, so the
	 * seed has to land on the version immediately before it.
	 */
	private static final String BEFORE_THE_CORRECTION = "119";

	private static final String DATABASE = "kms_v121_check";

	@Test
	@DisplayName("V120 clears a no-show claimed on an order nobody sent, and leaves the sent one alone")
	void theCorrectionRunsPerTenantAndOnlyOnUnsentOrders() throws SQLException {
		recreateDatabase();

		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(BEFORE_THE_CORRECTION)
				.load()
				.migrate();

		// Two temples, because the whole point of the loop is that it visits each of them. One
		// tenant would pass just as well against a migration that adopted the first tenant and
		// stopped, which is a real way to get this wrong.
		seedTemple("v121-north", "Heritage Fresh Dairy", 1);
		seedTemple("v121-south", "Govind Wholesale", 2);

		var result = Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.load()
				.migrate();
		assertThat(result.migrationsExecuted)
				.as("if this stops migrating anything, the guard has quietly stopped guarding")
				.isPositive();

		// The claim nobody was entitled to make is gone, in both temples.
		assertThat(countOf("""
				SELECT count(*) FROM purchase_orders
				WHERE vendor_abandoned AND sent_at IS NULL
				"""))
				.as("an order the vendor was never sent must not be held against them any more")
				.isZero();

		// And the correction is narrow. A cancellation the vendor genuinely walked away from is the
		// feature working as intended (T-124, Rajeev's fifth delivery scenario), and clearing that
		// too would silently delete every real no-show the temples had recorded.
		assertThat(countOf("""
				SELECT count(*) FROM purchase_orders
				WHERE vendor_abandoned AND sent_at IS NOT NULL
				"""))
				.as("a sent order's no-show is a real record and must survive the migration")
				.isEqualTo(2);

		// The activity trail is left exactly as it was. po_events is append-only by design (V26:85)
		// and the line records what a person actually did on the day; what was wrong was never that
		// the act happened, only that it scored somebody.
		// Scoped to the two orders the migration actually corrected — one per temple. Counting every
		// such line would also count the two sent orders' lines, which nothing was going to touch,
		// and the assertion would then pass without saying anything about the corrected rows.
		assertThat(countOf("""
				SELECT count(*) FROM po_events e
				JOIN purchase_orders p ON p.id = e.po_id
				WHERE p.po_number = 'PO-2026-0036'
				  AND e.detail LIKE '%recorded as never delivered by the vendor.'
				"""))
				.as("the history of the act is not rewritten, only the figure it fed")
				.isEqualTo(2);
	}

	// ---------------------------------------------------------------------

	/**
	 * A temple with two cancelled orders against one vendor: one that was sent and then abandoned,
	 * and one that was never sent at all. Seeded through the superuser, exactly as
	 * {@code TenantLoopMigrationIT} does — what is under test is the migration, not the seed, and a
	 * session setting would not survive from one statement to the next here.
	 */
	private void seedTemple(String slug, String vendorName, int n) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					VALUES ('%s', 'V120 %s', 12.97, 77.59, 'Asia/Kolkata')
					""".formatted(slug, slug));
			statement.execute("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					SELECT id, 'uid-%s', 'V120 Admin', '%s@example.com', '+919876500%d',
						   'TEMPLE_ADMIN', 'ACTIVE'
					FROM tenants WHERE slug = '%s'
					""".formatted(slug, slug, 200 + n, slug));
			statement.execute("""
					INSERT INTO vendors (tenant_id, name, phone)
					SELECT id, '%s', '+919812345678' FROM tenants WHERE slug = '%s'
					""".formatted(vendorName, slug));

			// The bad row: a draft that was cancelled with the box ticked. sent_at is null, which is
			// the whole of what makes it wrong.
			insertCancelledOrder(statement, slug, "PO-2026-0036", false);
			// The good row: sent, then cancelled because the vendor never came.
			insertCancelledOrder(statement, slug, "PO-2026-0037", true);
		}
	}

	private void insertCancelledOrder(Statement statement, String slug, String poNumber, boolean sent)
			throws SQLException {
		statement.execute("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, order_date,
						needed_by, created_by, sent_at, cancelled_at, cancel_reason, vendor_abandoned)
				SELECT t.id, '%s', v.id, 'CANCELLED', DATE '2026-09-01', DATE '2026-09-05', u.id,
					   %s, now(), 'never answered the phone', TRUE
				FROM tenants t
				JOIN vendors v ON v.tenant_id = t.id
				JOIN users u ON u.tenant_id = t.id
				WHERE t.slug = '%s'
				""".formatted(poNumber, sent ? "now()" : "NULL", slug));
		statement.execute("""
				INSERT INTO po_events (tenant_id, po_id, event_type, detail, actor_user_id)
				SELECT p.tenant_id, p.id, 'CANCELLED',
					   'never answered the phone — recorded as never delivered by the vendor.', p.created_by
				FROM purchase_orders p
				JOIN tenants t ON t.id = p.tenant_id
				WHERE t.slug = '%s' AND p.po_number = '%s'
				""".formatted(slug, poNumber));
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema here too, exactly as it does in the real one, so a
		// migration that only works when RLS is bypassed still fails.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private long countOf(String sql) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement();
				var rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
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
