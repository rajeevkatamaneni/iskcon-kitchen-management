package org.iskcon.kms.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The platform notice board (E9-S1), end to end and against a real database.
 *
 * <p>This is the only feature in the product that crosses tenant isolation on purpose, so the tests
 * that matter most are the ones that would pass vacuously if it were built wrong. They run through
 * the application's own DataSource as the unprivileged {@code kms_app} role — a superuser bypasses
 * row-level security entirely, whatever {@code FORCE ROW LEVEL SECURITY} says, and a suite run as
 * one would prove nothing about either the reach of a notice or the confinement of a dismissal.
 * Fixtures alone go through the privileged connection, which is also the only way to seed rows for
 * two temples at once.
 *
 * <p>Every rule in build brief §11 has a test here, including the two that are easiest to get
 * subtly wrong: dismissal is per <em>person</em> rather than per temple, and a withdrawal reaches
 * the people who had already cleared the original — which is precisely the set of people most likely
 * to have acted on it.
 */
@AutoConfigureMockMvc
@Import(PlatformNoticeIT.StubVerifierConfiguration.class)
class PlatformNoticeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private NoticeService noticeService;

	/** The application's own tenant-aware DataSource — unprivileged, and subject to every policy. */
	@Autowired
	private DataSource dataSource;

	private final ObjectMapper json = new ObjectMapper();

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("notice-bengaluru", "ISKCON South Bengaluru");
		templeB = insertTenant("notice-mayapur", "ISKCON Mayapur");

		// Two admins at the same temple: the whole point of per-person dismissal is that one of
		// them clearing a recall must not clear it for the other.
		insertUser(templeA, "uid-a-admin", "Gauranga Das", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-a-second", "Radhika Devi", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-a-cook", "Bhakta Ram", "KITCHEN_STAFF");
		insertUser(templeB, "uid-b-admin", "Nitai Das", "TEMPLE_ADMIN");
		insertUser(null, "uid-operator", "Platform Operator", "SUPER_ADMIN");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM platform_notice_dismissals");
		admin.execute("DELETE FROM platform_notices");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---------------------------------------------------------------------
	// Reach: the thing every other table in this schema is built to prevent.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("a notice raised by one temple is read by another, and names the temple that raised it")
	void oneTempleRaisesAndEveryTempleReads() throws Exception {
		signIn("uid-a-admin");
		raise("URGENT", "Adulterated ghee", "Batch 44/2026 from Sri Traders. Stop using it.");

		signIn("uid-b-admin");
		mvc.perform(get("/api/v1/notices/feed").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].subject").value("Adulterated ghee"))
				.andExpect(jsonPath("$[0].severity").value("URGENT"))
				// Named in the open. It is what a temple reading this rings, and one of the three
				// things standing in for the pre-moderation the board does without.
				.andExpect(jsonPath("$[0].raisedBy").value("ISKCON South Bengaluru"))
				.andExpect(jsonPath("$[0].mine").value(false))
				.andExpect(jsonPath("$[0].canWithdraw").value(false));
	}

	@Test
	@DisplayName("a cook reads the board too — a recall is about food, not about administration")
	void kitchenStaffReceiveNotices() throws Exception {
		signIn("uid-a-admin");
		raise("URGENT", "Adulterated ghee", "Stop using batch 44/2026.");

		signIn("uid-a-cook");
		mvc.perform(get("/api/v1/notices/feed").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("kitchen staff cannot raise one")
	void kitchenStaffCannotRaise() throws Exception {
		signIn("uid-a-cook");
		mvc.perform(post("/api/v1/notices").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"severity":"URGENT","subject":"Anything","body":"Anything at all."}
								"""))
				.andExpect(status().isForbidden());

		assertThat(admin.queryForObject("SELECT count(*) FROM platform_notices", Integer.class))
				.isZero();
	}

	// ---------------------------------------------------------------------
	// Dismissal is per person, and the window is thirty days.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("one admin dismissing leaves it standing for their colleague at the same temple")
	void dismissalIsPerPersonNotPerTemple() throws Exception {
		signIn("uid-b-admin");
		UUID notice = raise("IMPORTANT", "Supplier has closed", "Sri Traders have shut for a month.");

		signIn("uid-a-admin");
		dismiss(notice);
		assertThat(feedSize()).as("the person who dismissed it no longer sees it").isZero();

		// The colleague has not read it yet. A temple with three admins where the first clears a
		// recall before the others have seen it is a temple where two people never saw it.
		signIn("uid-a-second");
		assertThat(feedSize()).as("their colleague at the same temple still sees it").isEqualTo(1);
	}

	@Test
	@DisplayName("after thirty days it leaves Today, and stays on the board for good")
	void thirtyDayWindowOnTodayOnly() throws Exception {
		signIn("uid-b-admin");
		UUID notice = raise("INFORMATION", "Janmashtami advisory", "Expect heavier footfall.");
		backdate(notice, 31);

		signIn("uid-a-admin");
		assertThat(feedSize()).as("a month-old notice is no longer today's news").isZero();

		mvc.perform(get("/api/v1/notices").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].subject").value("Janmashtami advisory"));
	}

	@Test
	@DisplayName("a notice that was dismissed is still on the permanent board")
	void dismissedNoticeStaysOnTheBoard() throws Exception {
		signIn("uid-b-admin");
		UUID notice = raise("INFORMATION", "Janmashtami advisory", "Expect heavier footfall.");

		signIn("uid-a-admin");
		dismiss(notice);

		assertThat(feedSize()).isZero();
		mvc.perform(get("/api/v1/notices").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(1));
	}

	// ---------------------------------------------------------------------
	// Withdrawal — the safety valve that makes going without moderation defensible.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("the raising temple may withdraw its own; another temple may not")
	void onlyTheRaisingTempleOrAnOperatorMayWithdraw() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("URGENT", "Adulterated ghee", "Stop using batch 44/2026.");

		// Temple B holds RAISE_PLATFORM_NOTICE — this is the ownership check, not the permission.
		signIn("uid-b-admin");
		mvc.perform(withdrawRequest(notice, "Not ours to judge."))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4308"));

		signIn("uid-a-admin");
		mvc.perform(withdrawRequest(notice, "The batch numbers were ours alone."))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("a platform operator may withdraw anybody's — the takedown that replaces review")
	void operatorMayWithdrawAnyone() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("URGENT", "Something abusive", "Which should not have been posted.");

		signIn("uid-operator");
		mvc.perform(withdrawRequest(notice, "Abusive; taken down by the platform."))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT withdrawn_by_label FROM platform_notices WHERE id = ?", String.class, notice))
				.isEqualTo("the platform");
	}

	@Test
	@DisplayName("withdrawing twice is refused, not silently repeated")
	void withdrawingTwiceIsRefused() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("IMPORTANT", "Supplier has closed", "Sri Traders have shut.");

		mvc.perform(withdrawRequest(notice, "Posted in error.")).andExpect(status().isNoContent());
		mvc.perform(withdrawRequest(notice, "Posted in error again."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4966"));
	}

	@Test
	@DisplayName("a withdrawal reaches the very people who had already cleared the original")
	void withdrawalReachesThoseWhoSawTheOriginal() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("URGENT", "Adulterated ghee", "Stop using batch 44/2026.");

		// Temple B reads it, acts on it, and clears it.
		signIn("uid-b-admin");
		assertThat(feedSize()).isEqualTo(1);
		dismiss(notice);
		assertThat(feedSize()).isZero();

		signIn("uid-a-admin");
		mvc.perform(withdrawRequest(notice, "The batch was ours alone; no other temple is affected."))
				.andExpect(status().isNoContent());

		// The retraction travels the same rails as the notice did. Anything else leaves the temples
		// that acted on a recall the only ones never told it was called off.
		signIn("uid-b-admin");
		mvc.perform(get("/api/v1/notices/feed").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].withdrawn").value(true))
				.andExpect(jsonPath("$[0].withdrawnBy").value("ISKCON South Bengaluru"))
				.andExpect(jsonPath("$[0].withdrawnReason")
						.value("The batch was ours alone; no other temple is affected."));

		// And it can be cleared again, for good.
		dismiss(notice);
		assertThat(feedSize()).isZero();
	}

	@Test
	@DisplayName("a withdrawal is news even when the notice it retracts is older than the window")
	void withdrawalOfAnOldNoticeIsStillNews() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("URGENT", "Adulterated ghee", "Stop using batch 44/2026.");
		backdate(notice, 40);

		signIn("uid-b-admin");
		assertThat(feedSize()).as("the original has aged off Today").isZero();

		signIn("uid-a-admin");
		mvc.perform(withdrawRequest(notice, "Tested clean after all.")).andExpect(status().isNoContent());

		signIn("uid-b-admin");
		assertThat(feedSize()).as("but the retraction is today's news").isEqualTo(1);
	}

	@Test
	@DisplayName("a withdrawal without a reason is refused")
	void withdrawalNeedsAReason() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("IMPORTANT", "Supplier has closed", "Sri Traders have shut.");

		mvc.perform(post("/api/v1/notices/" + notice + "/withdraw")
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	// ---------------------------------------------------------------------
	// The audit trail, which is the second of the three things standing in for review.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("every raise and every withdrawal lands on the platform audit log")
	void raisingAndWithdrawingAreOnThePlatformLog() throws Exception {
		signIn("uid-a-admin");
		UUID notice = raise("URGENT", "Adulterated ghee", "Stop using batch 44/2026.");

		signIn("uid-operator");
		mvc.perform(withdrawRequest(notice, "Handled out of band.")).andExpect(status().isNoContent());

		Map<String, Object> raised = admin.queryForMap("""
				SELECT actor_label, entity_type, entity_id FROM platform_audit_events
				WHERE action = 'NOTICE_RAISED'
				""");
		assertThat(raised.get("entity_type")).isEqualTo("PLATFORM_NOTICE");
		assertThat(raised.get("entity_id")).isEqualTo(notice);
		// A temple admin, on the platform log. V66 widens the insert policy by exactly this much,
		// because raising a notice is the first act belonging to no temple that is not an operator's.
		assertThat((String) raised.get("actor_label")).contains("Gauranga Das");

		Map<String, Object> withdrawn = admin.queryForMap("""
				SELECT actor_label, reason FROM platform_audit_events WHERE action = 'NOTICE_WITHDRAWN'
				""");
		assertThat((String) withdrawn.get("actor_label")).contains("SUPER_ADMIN");
		assertThat(withdrawn.get("reason")).isEqualTo("Handled out of band.");

		// The raising temple's own log has it too: their admin should not have to ask an operator
		// what their own people posted to two hundred temples.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM audit_events WHERE action = 'NOTICE_RAISED' AND tenant_id = ?
				""", Integer.class, templeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("a temple admin who writes to the platform log still cannot read it")
	void writingToThePlatformLogIsNotReadingIt() throws Exception {
		signIn("uid-a-admin");
		raise("INFORMATION", "Janmashtami advisory", "Expect heavier footfall.");

		assertThat(admin.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
				.as("the entry is there").isEqualTo(1);

		// Read through the application's unprivileged connection, with exactly the scoping a signed-in
		// temple admin's request carries. V66 widened the insert side of that policy and nothing else,
		// so the log they just appended to stays invisible to them.
		JdbcTemplate app = new JdbcTemplate(dataSource);
		TenantContext.set(templeA);
		TenantContext.setAuthLookupUid("uid-a-admin");
		try {
			assertThat(app.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("a temple admin appends to the platform log and cannot read it").isZero();
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("deleting the raising temple leaves the notice standing, and takes its dismissals")
	void aNoticeOutlivesTheTempleThatRaisedIt() throws Exception {
		signIn("uid-b-admin");
		UUID notice = raise("IMPORTANT", "Supplier has closed", "Sri Traders have shut for a month.");
		dismiss(notice);

		// The whole-tenant purge, as E1-S15 runs it: SECURITY DEFINER, so it executes as the schema
		// owner under FORCE ROW LEVEL SECURITY rather than as a superuser. Two things could have gone
		// wrong here and are what this test is for — a dismissal table the purge cannot delete from
		// stalls the loop against the users foreign key, and a RESTRICT on the raising temple would
		// have made a notice a reason a temple could not be deleted at all.
		admin.query("SELECT delete_tenant_cascade(?)", (java.sql.ResultSet rs) -> {}, templeB);

		assertThat(admin.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, templeB))
				.as("the temple is gone").isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM platform_notice_dismissals WHERE notice_id = ?", Integer.class, notice))
				.as("and so are its people's dismissals").isZero();

		Map<String, Object> survivor = admin.queryForMap(
				"SELECT raised_by_tenant_id, raised_by_label FROM platform_notices WHERE id = ?", notice);
		assertThat(survivor.get("raised_by_tenant_id")).as("no longer pointing at a temple").isNull();
		assertThat(survivor.get("raised_by_label"))
				.as("but every temple that read it can still see who said it")
				.isEqualTo("ISKCON Mayapur");
	}

	// ---------------------------------------------------------------------
	// A notice nobody had to remember to post.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("automation raises one attributed to the platform, with no person behind it")
	void automationRaisesAPlatformNotice() throws Exception {
		// The shape a maintenance-window job in the worker produces: no signed-in person, no temple,
		// and therefore no auth_uid on the connection — which is the only shape V66 admits from an
		// unauthenticated one.
		UUID id = noticeService.raiseFromPlatform(
				NoticeSeverity.IMPORTANT, "Planned maintenance on Sunday",
				"The app will be unavailable between 2am and 4am.");

		signIn("uid-a-admin");
		mvc.perform(get("/api/v1/notices/feed").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].raisedBy").value("the platform"))
				.andExpect(jsonPath("$[0].id").value(id.toString()));
	}

	// ---------------------------------------------------------------------

	private UUID raise(String severity, String subject, String body) throws Exception {
		String response = mvc.perform(post("/api/v1/notices")
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"severity":"%s","subject":"%s","body":"%s"}
								""".formatted(severity, subject, body)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.readTree(response).get("id").asText());
	}

	private void dismiss(UUID notice) throws Exception {
		mvc.perform(post("/api/v1/notices/" + notice + "/dismiss")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.RequestBuilder withdrawRequest(UUID notice, String reason) {
		return post("/api/v1/notices/" + notice + "/withdraw")
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"%s\"}".formatted(reason));
	}

	private int feedSize() throws Exception {
		String response = mvc.perform(get("/api/v1/notices/feed")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode tree = json.readTree(response);
		return tree.size();
	}

	/** Ages a notice by moving its creation backwards; the window is what is under test, not time. */
	private void backdate(UUID notice, int days) {
		admin.update(
				"UPDATE platform_notices SET created_at = now() - make_interval(days => ?) WHERE id = ?",
				days, notice);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String name, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, '+919876500083', ?, 'ACTIVE')
				""", tenantId, uid, name, uid + "@example.com", role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
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
				throw new InvalidTokenException("no such token");
			}
			return subject;
		}
	}
}
