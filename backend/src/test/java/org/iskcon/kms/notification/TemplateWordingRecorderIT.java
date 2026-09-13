package org.iskcon.kms.notification;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.ops.WhatsAppTemplateCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The start-up recorder behind the operator's "Wording first seen by the app" (T-177, V130).
 *
 * <p><strong>No extra Spring context.</strong> Two processes starting at once are two recorder
 * instances, each on its own connection as the unprivileged {@code kms_app} role, inside the shared
 * context this base class already starts. A second context per "process" would be two schedulers and
 * two pools for nothing the test needs.
 *
 * <p><strong>How the race is made deterministic.</strong> Each instance's connection is wrapped so
 * that an INSERT, the moment it is about to run, counts down one latch and waits on a second. The test
 * releases the second only when both have arrived. So both processes have done everything they do
 * before writing, and then write at once. With the insert-if-absent that ships, there is nothing
 * before the write, and both succeed. With a read-then-write, both reads happen before the latch and
 * both see nothing, so both try to insert every row, and one of them fails on the primary key. That
 * is negative control 1, and "both starts recorded without an error" is the assertion it turns red.
 *
 * <p>Every test clears the table first as the container superuser, which the append-only trigger does
 * not bind (it refuses only {@code kms_app}), so each starts from a table with no rows.
 */
class TemplateWordingRecorderIT extends AbstractIntegrationTest {

	private static final String TABLE = "whatsapp_template_wording_seen";

	@Autowired
	private TemplateWordingRecorder springRecorder;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		admin.update("DELETE FROM " + TABLE);
	}

	@Test
	@DisplayName("two processes starting at once, released together at their insert, leave one row per wording and both succeed")
	void twoProcessesStartingAtOnce() throws Exception {
		CountDownLatch arrived = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		Map<String, String> wording = TemplateWordingRecorder.currentWording();

		try (Connection a = appConnection(); Connection b = appConnection()) {
			TemplateWordingRecorder api = new TemplateWordingRecorder(gatedAtInsert(a, arrived, release));
			TemplateWordingRecorder worker = new TemplateWordingRecorder(gatedAtInsert(b, arrived, release));

			Instant before = databaseNow();
			ExecutorService starts = Executors.newFixedThreadPool(2);
			try {
				Future<Boolean> apiStart = starts.submit(() -> api.record(wording));
				Future<Boolean> workerStart = starts.submit(() -> worker.record(wording));

				assertThat(arrived.await(30, SECONDS))
						.as("both processes reached their insert before either was let go")
						.isTrue();
				release.countDown();

				boolean apiRecorded = apiStart.get(60, SECONDS);
				boolean workerRecorded = workerStart.get(60, SECONDS);
				Instant after = databaseNow();

				assertThat(List.of(apiRecorded, workerRecorded))
						.as("both starts recorded without an error")
						.containsExactly(true, true);

				Map<String, List<Instant>> rows = rowsByWording();
				assertThat(rows.keySet())
						.as("exactly the current wordings, one key each")
						.containsExactlyInAnyOrderElementsOf(keys(wording));
				assertThat(rows.values())
						.as("one row per (template name, fingerprint)")
						.allSatisfy(times -> assertThat(times).hasSize(1));
				assertThat(rows.values())
						.as("each time is from this start, on the database clock")
						.allSatisfy(times -> assertThat(times.get(0)).isBetween(before, after));
			} finally {
				starts.shutdownNow();
			}
		}
	}

	@Test
	@DisplayName("a later start with unchanged wording adds nothing and keeps the earlier time")
	void laterStartAddsNothing() throws Exception {
		try (Connection c = appConnection()) {
			TemplateWordingRecorder recorder = new TemplateWordingRecorder(plain(c));
			Map<String, String> wording = TemplateWordingRecorder.currentWording();

			assertThat(recorder.record(wording)).isTrue();
			Map<String, List<Instant>> first = rowsByWording();
			assertThat(first).hasSize(NotificationTemplate.values().length);

			assertThat(recorder.record(wording)).isTrue();
			assertThat(rowsByWording())
					.as("same rows, same first-seen times")
					.isEqualTo(first);
		}
	}

	@Test
	@DisplayName("a changed wording for one template adds exactly one row, and the catalogue then shows when it changed")
	void changedWordingAddsOneRow() throws Exception {
		try (Connection c = appConnection()) {
			JdbcTemplate app = plain(c);
			TemplateWordingRecorder recorder = new TemplateWordingRecorder(app);

			Map<String, String> earlierRelease = new TreeMap<>(TemplateWordingRecorder.currentWording());
			earlierRelease.put("volunteer_shift_reminder", "sha256:the-wording-before-this-release");
			assertThat(recorder.record(earlierRelease)).isTrue();
			Map<String, List<Instant>> before = rowsByWording();

			assertThat(recorder.record(TemplateWordingRecorder.currentWording())).isTrue();
			Map<String, List<Instant>> after = rowsByWording();

			Map<String, List<Instant>> added = new LinkedHashMap<>(after);
			added.keySet().removeAll(before.keySet());
			String currentKey = "volunteer_shift_reminder " + NotificationTemplate.VOLUNTEER_SHIFT_REMINDER.whatsappFingerprint("en");
			assertThat(added.keySet()).as("the one new row").containsExactly(currentKey);
			assertThat(after).as("nothing earlier moved").containsAllEntriesOf(before);

			Instant oldSeen = before.get("volunteer_shift_reminder sha256:the-wording-before-this-release").get(0);
			Instant newSeen = added.get(currentKey).get(0);
			assertThat(newSeen).isAfter(oldSeen);

			WhatsAppTemplateCatalogue.Catalogue catalogue = new WhatsAppTemplateCatalogue(app).read();
			WhatsAppTemplateCatalogue.Entry shiftReminder = entry(catalogue, "volunteer_shift_reminder");
			assertThat(shiftReminder.wordingFirstSeenAt()).isEqualTo(oldSeen);
			assertThat(shiftReminder.wordingLastChangedAt()).isEqualTo(newSeen);

			WhatsAppTemplateCatalogue.Entry unchanged = entry(catalogue, "po_delivery");
			assertThat(unchanged.wordingFirstSeenAt()).isEqualTo(oldSeen);
			assertThat(unchanged.wordingLastChangedAt()).as("one wording recorded, so no change").isNull();
			assertThat(catalogue.trackingSince()).isEqualTo(oldSeen);
		}
	}

	@Test
	@DisplayName("not temple data: the app role inserts and reads it, the table has no tenant_id and no row-level security")
	void appRoleInsertsAndReads() throws Exception {
		try (Connection c = appConnection()) {
			JdbcTemplate app = plain(c);
			assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);

			assertThat(new TemplateWordingRecorder(app).record(TemplateWordingRecorder.currentWording())).isTrue();
			assertThat(app.update("INSERT INTO " + TABLE + " (template_name, fingerprint) VALUES ('a_test', 'sha256:x')"))
					.isEqualTo(1);
			assertThat(app.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class))
					.isEqualTo(NotificationTemplate.values().length + 1);
		}

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = ? AND column_name = 'tenant_id'
				""", Integer.class, TABLE)).as("no tenant_id column").isZero();
		assertThat(admin.queryForObject(
				"SELECT relrowsecurity FROM pg_class WHERE relname = ? AND relnamespace = 'public'::regnamespace",
				Boolean.class, TABLE)).as("no row-level security").isFalse();
	}

	@Test
	@DisplayName("append-only: the app role's UPDATE of a first-seen time is refused")
	void appRoleCannotUpdate() throws Exception {
		try (Connection c = appConnection()) {
			JdbcTemplate app = plain(c);
			assertThat(new TemplateWordingRecorder(app).record(TemplateWordingRecorder.currentWording())).isTrue();
			assertThat(app.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class))
					.as("rows exist, so a refusal cannot be an UPDATE that matched nothing")
					.isPositive();

			assertThatThrownBy(() -> app.update(
					"UPDATE " + TABLE + " SET first_seen_at = first_seen_at - interval '1 year'"))
					.as("refused by the append-only trigger, as PostgreSQL words it")
					.rootCause().hasMessageContaining("append-only");
		}
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM " + TABLE + " WHERE first_seen_at < now() - interval '1 day'", Integer.class))
				.as("no time moved").isZero();
	}

	@Test
	@DisplayName("append-only: the app role's DELETE of a recorded wording is refused")
	void appRoleCannotDelete() throws Exception {
		try (Connection c = appConnection()) {
			JdbcTemplate app = plain(c);
			assertThat(new TemplateWordingRecorder(app).record(TemplateWordingRecorder.currentWording())).isTrue();

			assertThatThrownBy(() -> app.update("DELETE FROM " + TABLE))
					.as("refused by the append-only trigger, as PostgreSQL words it")
					.rootCause().hasMessageContaining("append-only");
		}
		assertThat(admin.queryForObject("SELECT count(*) FROM " + TABLE, Integer.class))
				.isEqualTo(NotificationTemplate.values().length);
	}

	@Test
	@DisplayName("a failed write is logged and the start carries on")
	void failedWriteDoesNotStopTheStart() throws Exception {
		Connection closed = appConnection();
		closed.close();
		TemplateWordingRecorder recorder = new TemplateWordingRecorder(plain(closed));

		assertThat(recorder.record(TemplateWordingRecorder.currentWording())).isFalse();
		assertThatCode(() -> recorder.run(null)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the application's own recorder is a start-up runner and writes through the app's connection")
	void springBeanRecords() throws Exception {
		assertThat(springRecorder).isInstanceOf(ApplicationRunner.class);

		springRecorder.run(null);

		assertThat(rowsByWording().keySet()).containsExactlyInAnyOrderElementsOf(keys(TemplateWordingRecorder.currentWording()));
	}

	@Test
	@DisplayName("the recorder fingerprints in the language Save and Reload register templates in")
	void sameLanguageAsTheSettingsService() throws Exception {
		Field language = TenantWhatsAppSettingsService.class.getDeclaredField("TEMPLATE_LANGUAGE");
		language.setAccessible(true);
		assertThat(TemplateWordingRecorder.LANGUAGE).isEqualTo(language.get(null));
	}

	// ---------------------------------------------------------------------

	private static Connection appConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
	}

	private static JdbcTemplate plain(Connection connection) {
		return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
	}

	/**
	 * A JdbcTemplate whose INSERTs stop at the door: each counts down {@code arrived} and waits for
	 * {@code release}. Everything else, a SELECT included, runs straight through.
	 */
	private static JdbcTemplate gatedAtInsert(Connection real, CountDownLatch arrived, CountDownLatch release) {
		Connection proxy = (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
				(p, method, args) -> {
					Object result = call(real, method, args);
					if ("prepareStatement".equals(method.getName()) && args != null
							&& args[0] instanceof String sql && sql.stripLeading().startsWith("INSERT")) {
						PreparedStatement statement = (PreparedStatement) result;
						return Proxy.newProxyInstance(
								PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
								(p2, m2, a2) -> {
									if (m2.getName().startsWith("execute")) {
										arrived.countDown();
										if (!release.await(30, SECONDS)) {
											throw new SQLException("the other process never arrived");
										}
									}
									return call(statement, m2, a2);
								});
					}
					return result;
				});
		return plain(proxy);
	}

	private static Object call(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private Instant databaseNow() {
		return admin.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
	}

	/** "name fingerprint" → every first-seen time recorded under that key. */
	private Map<String, List<Instant>> rowsByWording() {
		Map<String, List<Instant>> rows = new TreeMap<>();
		admin.query("SELECT template_name, fingerprint, first_seen_at FROM " + TABLE, rs -> {
			rows.computeIfAbsent(rs.getString(1) + " " + rs.getString(2), k -> new java.util.ArrayList<>())
					.add(rs.getObject(3, OffsetDateTime.class).toInstant());
		});
		return rows;
	}

	private static List<String> keys(Map<String, String> wording) {
		return wording.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList();
	}

	private static WhatsAppTemplateCatalogue.Entry entry(WhatsAppTemplateCatalogue.Catalogue catalogue, String name) {
		return catalogue.templates().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
	}
}
