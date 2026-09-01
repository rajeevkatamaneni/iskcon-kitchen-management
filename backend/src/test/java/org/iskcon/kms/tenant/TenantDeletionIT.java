package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobDataMap;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.iskcon.kms.auth.TokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Deleting a temple erases every trace of it — including its append-only rows and its queued and
 * scheduled work — while leaving every other temple untouched, and records a durable proof on the
 * platform audit log. Verified against a real database because every guard it crosses (ON DELETE
 * RESTRICT, append-only, and a Quartz job store that knows nothing about tenants) is a database
 * behaviour; mocking them would prove nothing.
 */
@AutoConfigureMockMvc
@Import(TenantDeletionIT.StubVerifierConfiguration.class)
class TenantDeletionIT extends AbstractIntegrationTest {

	// Every tenant-owned table seedTemple() writes to. audit_events is append-only and references
	// users (ON DELETE RESTRICT), so it exercises the append-only lift and the FK-ordered purge.
	private static final List<String> SEEDED_TABLES =
			List.of("users", "ingredients", "notifications", "audit_events");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private TenantSecretStore secrets;

	private JdbcTemplate admin;

	private final List<String> seededJobs = new java.util.ArrayList<>();

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
	}

	@AfterEach
	void tearDown() {
		// As the superuser, so append-only and RLS don't obstruct the cleanup.
		// Only the jobs this class seeded, child rows before parents — none of those foreign keys
		// cascades, and the job store is shared with the class that runs a real scheduler.
		for (String jobName : seededJobs) {
			admin.update("DELETE FROM qrtz_simple_triggers WHERE trigger_name = ?", jobName + "-trigger");
			admin.update("DELETE FROM qrtz_cron_triggers WHERE trigger_name = ?", jobName + "-trigger");
			admin.update("DELETE FROM qrtz_fired_triggers WHERE job_name = ?", jobName);
			admin.update("DELETE FROM qrtz_triggers WHERE job_name = ?", jobName);
			admin.update("DELETE FROM qrtz_job_details WHERE job_name = ?", jobName);
		}
		seededJobs.clear();
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM notifications");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("deleting a temple erases all its data, keeps the audit proof, and spares other temples")
	void deleteWipesEverythingAndSparesOthers() throws Exception {
		UUID doomed = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID survivor = seedTemple("krishna-balaram", "Sri Krishna Balaram Temple");
		signInAsSuperAdmin();
		takeExport(doomed);

		mvc.perform(authed(delete("/api/v1/tenants/{id}", doomed))).andExpect(status().isNoContent());

		// The doomed temple is gone from every seeded table, and the temple row itself.
		assertThat(rowsFor(doomed)).isZero();
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", doomed)).isZero();

		// The only durable record is on the platform log, which is not tenant-owned.
		assertThat(count(
				"SELECT count(*) FROM platform_audit_events WHERE action = 'TENANT_DELETED' AND entity_id = ?",
				doomed)).isEqualTo(1);

		// The other temple is entirely untouched — one row in each of the four seeded tables.
		assertThat(rowsFor(survivor)).isEqualTo(SEEDED_TABLES.size());
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", survivor)).isEqualTo(1);
	}

	@Test
	@DisplayName("a deleted temple's secrets go too, and a refused deletion leaves them alone")
	void secretsAreErasedWithTheTempleAndOnlyWithIt() throws Exception {
		UUID doomed = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID survivor = seedTemple("krishna-balaram", "Sri Krishna Balaram Temple");
		secrets.put(doomed, TenantSecretStore.Kind.PAYMENT_KEY_SECRET, "doomed-key-secret");
		secrets.put(doomed, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET, "doomed-webhook-secret");
		secrets.put(survivor, TenantSecretStore.Kind.PAYMENT_KEY_SECRET, "survivor-key-secret");
		signInAsSuperAdmin();

		// Refused for want of an export: the temple lives, so its credentials must still work.
		mvc.perform(authed(delete("/api/v1/tenants/{id}", doomed))).andExpect(status().isConflict());
		assertThat(secrets.get(doomed, TenantSecretStore.Kind.PAYMENT_KEY_SECRET)).isPresent();

		takeExport(doomed);
		mvc.perform(authed(delete("/api/v1/tenants/{id}", doomed))).andExpect(status().isNoContent());

		// Erased only once the purge has actually committed, and only this temple's.
		assertThat(secrets.get(doomed, TenantSecretStore.Kind.PAYMENT_KEY_SECRET)).isEmpty();
		assertThat(secrets.get(doomed, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET)).isEmpty();
		assertThat(secrets.get(survivor, TenantSecretStore.Kind.PAYMENT_KEY_SECRET))
				.contains("survivor-key-secret");
	}

	@Test
	@DisplayName("deleting without a recent export is refused, and nothing is touched")
	void deleteRequiresAnExport() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		signInAsSuperAdmin();

		mvc.perform(authed(delete("/api/v1/tenants/{id}", temple)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4941"));

		// Refused means refused: the temple and every one of its rows are still there.
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", temple)).isEqualTo(1);
		assertThat(rowsFor(temple)).isEqualTo(SEEDED_TABLES.size());
		assertThat(count(
				"SELECT count(*) FROM platform_audit_events WHERE action = 'TENANT_DELETED' AND entity_id = ?",
				temple)).isZero();
	}

	@Test
	@DisplayName("an export older than the window does not count as a safeguard")
	void staleExportDoesNotUnlockDeletion() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		signInAsSuperAdmin();
		takeExport(temple);

		// The temple has traded since; yesterday's copy is not a copy of what would be destroyed.
		admin.update("""
				UPDATE platform_audit_events SET created_at = now() - interval '25 hours'
				WHERE action = 'TENANT_EXPORTED' AND entity_id = ?
				""", temple);

		mvc.perform(authed(delete("/api/v1/tenants/{id}", temple)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4941"));

		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", temple)).isEqualTo(1);
	}

	@Test
	@DisplayName("exporting one temple does not unlock deleting a different one")
	void exportIsPerTemple() throws Exception {
		UUID exported = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID other = seedTemple("krishna-balaram", "Sri Krishna Balaram Temple");
		signInAsSuperAdmin();
		takeExport(exported);

		mvc.perform(authed(delete("/api/v1/tenants/{id}", other)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4941"));

		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", other)).isEqualTo(1);
	}

	@Test
	@DisplayName("a temple admin cannot delete a temple")
	void templeAdminIsForbidden() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		stubVerifier.accept("uid-radha-govinda"); // the temple's own admin, seeded by seedTemple

		mvc.perform(authed(delete("/api/v1/tenants/{id}", temple))).andExpect(status().isForbidden());

		// And nothing was deleted.
		assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", temple)).isEqualTo(1);
	}

	@Test
	@DisplayName("deleting an unknown temple is a 404, not a silent success")
	void deletingUnknownTempleIsNotFound() throws Exception {
		signInAsSuperAdmin();

		mvc.perform(authed(delete("/api/v1/tenants/{id}", UUID.randomUUID())))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("deleting a temple takes its scheduled work with it, and leaves everyone else's alone")
	void deleteRemovesTheTemplesScheduledJobs() throws Exception {
		UUID doomed = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID survivor = seedTemple("krishna-balaram", "Sri Krishna Balaram Temple");
		String doomedDocument = seedTenantJob("generate-document", doomed);
		String doomedSend = seedTenantJob("send", doomed);
		String survivorCalendar = seedTenantJob("calendar-precompute", survivor);
		String nightly = seedGlobalJob("shopping-list-regenerate");
		signInAsSuperAdmin();
		takeExport(doomed);

		mvc.perform(authed(delete("/api/v1/tenants/{id}", doomed))).andExpect(status().isNoContent());

		// Not one row of the doomed temple's schedule survives — job, trigger, the trigger's own
		// detail row, or the fired-trigger row a worker was left holding it by.
		assertThat(quartzRowsFor(doomedDocument)).isZero();
		assertThat(quartzRowsFor(doomedSend)).isZero();

		// The other temple's queued work, and the nightly sweep that belongs to no temple at all,
		// are exactly as they were: four rows each.
		assertThat(quartzRowsFor(survivorCalendar)).isEqualTo(4);
		assertThat(quartzRowsFor(nightly)).isEqualTo(4);
	}

	@Test
	@DisplayName("the orphan sweep clears jobs of temples already deleted, and is a no-op otherwise")
	void orphanSweepClearsOnlyJobsWithNoTemple() {
		UUID living = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		String livingCalendar = seedTenantJob("calendar-precompute", living);
		String nightly = seedGlobalJob("heartbeat-sweep");

		// A temple deleted before V86 existed: its work is still here, still firing at nothing.
		String departed = seedTenantJob("generate-document", UUID.randomUUID());

		sweepOrphans();

		assertThat(quartzRowsFor(departed)).isZero();
		assertThat(quartzRowsFor(livingCalendar)).isEqualTo(4);
		assertThat(quartzRowsFor(nightly)).isEqualTo(4);

		// Run it again and it finds nothing — which is what it does on a database whose every
		// temple still exists, and on a clean one, where there are no Quartz rows to begin with.
		assertThat(sweepOrphans()).isZero();
		assertThat(quartzRowsFor(livingCalendar)).isEqualTo(4);
		assertThat(quartzRowsFor(nightly)).isEqualTo(4);
	}

	// ---------------------------------------------------------------------

	/**
	 * A job as Quartz itself stores one for a temple: the tenant id lives in the serialized
	 * JobDataMap and nowhere else, so this seeds a real serialized map rather than a stand-in.
	 * Given a trigger, that trigger's simple-trigger detail row, and a fired-trigger row — four
	 * rows in all, which is what {@link #quartzRowsFor} counts.
	 */
	private String seedTenantJob(String prefix, UUID tenantId) {
		JobDataMap data = new JobDataMap();
		data.put(KmsJob.TENANT_KEY, tenantId.toString());
		String jobName = insertJob(prefix, data);
		insertTrigger(jobName, "SIMPLE");
		admin.update("""
				INSERT INTO qrtz_simple_triggers
					(sched_name, trigger_name, trigger_group, repeat_count, repeat_interval, times_triggered)
				VALUES ('kms-scheduler', ?, 'DEFAULT', 0, 0, 0)
				""", jobName + "-trigger");
		return jobName;
	}

	/** A job with no temple in it — a nightly sweep, which must survive any deletion. */
	private String seedGlobalJob(String prefix) {
		String jobName = insertJob(prefix, new JobDataMap());
		insertTrigger(jobName, "CRON");
		admin.update("""
				INSERT INTO qrtz_cron_triggers (sched_name, trigger_name, trigger_group, cron_expression, time_zone_id)
				VALUES ('kms-scheduler', ?, 'DEFAULT', '0 0 4 * * ?', 'Asia/Kolkata')
				""", jobName + "-trigger");
		return jobName;
	}

	// The job store is shared with every other test class in this container — one of them runs a
	// real scheduler — so seeded jobs take a unique name and are removed by name, never by
	// emptying the tables.
	private String insertJob(String prefix, JobDataMap data) {
		String jobName = prefix + "-" + UUID.randomUUID();
		admin.update("""
				INSERT INTO qrtz_job_details
					(sched_name, job_name, job_group, job_class_name, is_durable, is_nonconcurrent,
					 is_update_data, requests_recovery, job_data)
				VALUES ('kms-scheduler', ?, 'DEFAULT', 'org.iskcon.kms.jobs.HeartbeatJob',
						false, false, false, true, ?)
				""", jobName, serialize(data));
		seededJobs.add(jobName);
		return jobName;
	}

	private void insertTrigger(String jobName, String type) {
		admin.update("""
				INSERT INTO qrtz_triggers
					(sched_name, trigger_name, trigger_group, job_name, job_group, next_fire_time,
					 trigger_state, trigger_type, start_time)
				VALUES ('kms-scheduler', ?, 'DEFAULT', ?, 'DEFAULT', 1, 'WAITING', ?, 1)
				""", jobName + "-trigger", jobName, type);
		admin.update("""
				INSERT INTO qrtz_fired_triggers
					(sched_name, entry_id, trigger_name, trigger_group, instance_name, fired_time,
					 sched_time, priority, state, job_name, job_group, is_nonconcurrent, requests_recovery)
				VALUES ('kms-scheduler', ?, ?, 'DEFAULT', 'test-worker', 1, 1, 5, 'EXECUTING', ?,
						'DEFAULT', false, true)
				""", jobName + "-entry", jobName + "-trigger", jobName);
	}

	/** Exactly how Quartz writes job data with {@code useProperties} off, which is our setting. */
	private static byte[] serialize(JobDataMap data) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(data);
		} catch (IOException e) {
			throw new IllegalStateException("Could not serialize the job data", e);
		}
		return bytes.toByteArray();
	}

	/** Runs what V86's final statement runs: the sweep of every temple that no longer exists. */
	private int sweepOrphans() {
		Integer deleted =
				admin.queryForObject("SELECT delete_tenant_scheduled_jobs(NULL::uuid)", Integer.class);
		return deleted == null ? 0 : deleted;
	}

	/** Every Quartz row belonging to one seeded job: four when it is whole, none when it has gone. */
	private int quartzRowsFor(String jobName) {
		Integer n = admin.queryForObject("""
				SELECT (SELECT count(*) FROM qrtz_job_details     WHERE job_name = ?)
				     + (SELECT count(*) FROM qrtz_triggers        WHERE job_name = ?)
				     + (SELECT count(*) FROM qrtz_fired_triggers  WHERE job_name = ?)
				     + (SELECT count(*) FROM qrtz_simple_triggers WHERE trigger_name = ?)
				     + (SELECT count(*) FROM qrtz_cron_triggers   WHERE trigger_name = ?)
				""", Integer.class, jobName, jobName, jobName, jobName + "-trigger", jobName + "-trigger");
		return n == null ? 0 : n;
	}

	// ---------------------------------------------------------------------

	/** A temple with a row in each seeded table, including the append-only audit log. */
	private UUID seedTemple(String slug, String name) {
		UUID temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);

		UUID user = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Temple Admin', ?, '+919876500050', 'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, temple, "uid-" + slug, slug + "@example.com");

		admin.update(
				"INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, 'Rice', 'Grains', 'KG')",
				temple);
		admin.update("""
				INSERT INTO notifications (tenant_id, recipient_label, to_phone, template, preferred_channel, status)
				VALUES (?, 'Test Devotee', '+919876500051', 'SHIFT_REMINDER', 'WHATSAPP', 'SENT')
				""", temple);
		// Append-only, and references the user above — so the purge must lift append-only and delete
		// this before it can delete the user.
		admin.update("""
				INSERT INTO audit_events (tenant_id, actor_user_id, actor_label, action, entity_type, entity_id)
				VALUES (?, ?, 'Temple Admin', 'ROLE_CHANGED', 'USER', ?)
				""", temple, user, user);

		return temple;
	}

	/** Deletion is gated on a recent copy (E1-S15, D6), so every deletion test takes one first. */
	private void takeExport(UUID tenantId) throws Exception {
		mvc.perform(authed(get("/api/v1/tenants/{id}/export", tenantId))).andExpect(status().isOk());
	}

	private int rowsFor(UUID tenantId) {
		int total = 0;
		for (String table : SEEDED_TABLES) {
			total += count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", tenantId);
		}
		return total;
	}

	private int count(String sql, UUID id) {
		Integer n = admin.queryForObject(sql, Integer.class, id);
		return n == null ? 0 : n;
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com', '+919000000001',
						'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
