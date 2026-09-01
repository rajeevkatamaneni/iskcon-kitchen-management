package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantAwareDataSource;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Dated, attributed, append-only conduct notes on a staff record (E6-S16), through the full stack.
 *
 * <p>Three things are worth proving here and none of them can be proved without a real database.
 *
 * <p><b>That it is genuinely permanent.</b> Not that the service declines to offer an edit — that
 * an editable employment note is worth nothing on the day it matters, so the refusal has to hold
 * against somebody with a psql prompt and the application's own credentials. It is checked the way
 * the stock ledger and the vendor history are: through the unprivileged {@code kms_app} role, with
 * no Java in the way.
 *
 * <p><b>That the permission is real.</b> A Kitchen Manager holds the roster and a Kitchen Staff
 * member holds the kitchen, and both are refused at both doors — checked endpoint by endpoint rather
 * than trusted to the fact that they carry the same annotation.
 *
 * <p><b>That it has nothing to do with the cross-temple ban.</b> Asserted structurally, so that a
 * later change wiring the two together fails here rather than shipping quietly.
 */
@AutoConfigureMockMvc
@Import(StaffConductNoteIT.StubVerifierConfiguration.class)
class StaffConductNoteIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Distinctive enough that finding it anywhere it does not belong is unambiguous. */
	private static final String PRIVATE_REMARK =
			"Arrived late three mornings running and was short with the sevaks. Spoken to on the 3rd.";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		// Its own slug: the container is shared by every test class in the JVM.
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda-conduct', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-conduct-admin", "Temple Admin", "conduct-admin@example.com",
				"+919876520001", "TEMPLE_ADMIN");
		signIn("uid-conduct-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM staff_conduct_notes");
		admin.execute("DELETE FROM employment_bans");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The note itself -------------------------------------------------

	@Test
	@DisplayName("a note comes back with who wrote it and when, newest first")
	void notesAreDatedAttributedAndNewestFirst() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");

		mvc.perform(addNote(staff, "Left the gas on overnight.")).andExpect(status().isCreated());
		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/staff/members/{id}/conduct-notes", staff)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				// Newest first: the question being asked is almost always "what happened lately".
				.andExpect(jsonPath("$[0].body").value(PRIVATE_REMARK))
				.andExpect(jsonPath("$[0].authorName").value("Temple Admin"))
				.andExpect(jsonPath("$[0].createdAt").exists())
				.andExpect(jsonPath("$[1].body").value("Left the gas on overnight."))
				// The refusal of everything else, asserted rather than merely not built: a rating or
				// a category appearing here is the design being reopened without anybody saying so.
				.andExpect(jsonPath("$[0].severity").doesNotExist())
				.andExpect(jsonPath("$[0].category").doesNotExist())
				.andExpect(jsonPath("$[0].noteType").doesNotExist())
				.andExpect(jsonPath("$[0].acknowledgedAt").doesNotExist());
	}

	@Test
	@DisplayName("a note of nothing but spaces is refused, because it would be permanent")
	void blankNoteIsRefused() throws Exception {
		String staff = hire("Radha Devi", "COOK");

		mvc.perform(addNote(staff, "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4012"));

		mvc.perform(authed(get("/api/v1/staff/members/{id}/conduct-notes", staff)))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("writing a conduct note leaves staff_profiles.notes exactly as it was")
	void theOldNotesColumnIsLeftAlone() throws Exception {
		String staff = hire("Murari Das", "COOK");
		admin.update("UPDATE staff_profiles SET notes = ? WHERE id = ?::uuid",
				"Prefers the early shift.", staff);

		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		assertThat(admin.queryForObject(
				"SELECT notes FROM staff_profiles WHERE id = ?::uuid", String.class, staff))
				.as("notes stays what it always was — this feature neither repurposes nor migrates it")
				.isEqualTo("Prefers the early shift.");
	}

	// ---- Permanence, proved against the application's own role -----------

	@Test
	@DisplayName("the note is append-only — the application role cannot edit or erase one")
	void notesAreAppendOnly() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		// Through kms_app itself, with the tenant set so that row-level security *permits* the write.
		// What then refuses it is the append-only trigger, which is the guarantee under test — not
		// RLS quietly matching zero rows.
		asApplication(app -> {
			assertThatThrownBy(() ->
					app.update("UPDATE staff_conduct_notes SET body = 'a kinder version'"))
					.as("append-only: UPDATE must be refused")
					.hasStackTraceContaining("append-only");
			assertThatThrownBy(() -> app.update("DELETE FROM staff_conduct_notes"))
					.as("append-only: DELETE must be refused")
					.hasStackTraceContaining("append-only");
		});

		mvc.perform(authed(get("/api/v1/staff/members/{id}/conduct-notes", staff)))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].body").value(PRIVATE_REMARK));
	}

	@Test
	@DisplayName("the table's owner keeps the privileges PostgreSQL's own foreign keys need")
	void appendOnlyDoesNotBreakForeignKeys() {
		// V50: a foreign key pointing at an append-only table is checked as that table's owner and
		// takes a FOR KEY SHARE lock, which needs UPDATE or DELETE. Revoking them instead of using
		// the trigger is what once made deleting an ingredient impossible on a real deployment.
		String owner = admin.queryForObject(
				"SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.staff_conduct_notes'::regclass",
				String.class);
		assertThat(admin.queryForObject(
				"SELECT has_table_privilege(?, 'public.staff_conduct_notes'::regclass, 'UPDATE')",
				Boolean.class, owner))
				.as("its owner (%s) must keep UPDATE, or a staff record can never be removed", owner)
				.isTrue();
		assertThat(admin.queryForObject(
				"SELECT has_table_privilege(?, 'public.staff_conduct_notes'::regclass, 'DELETE')",
				Boolean.class, owner))
				.as("its owner (%s) must keep DELETE, for the same reason", owner)
				.isTrue();
	}

	// ---- Its own permission, and who does not hold it --------------------

	@Test
	@DisplayName("a kitchen manager may run the roster and still not read a colleague's conduct note")
	void kitchenManagerIsRefusedBothDoors() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		signInAs("uid-conduct-manager", "Kitchen Manager", "conduct-manager@example.com",
				"+919876520011", "KITCHEN_MANAGER");

		mvc.perform(authed(get("/api/v1/staff/members/{id}/conduct-notes", staff)))
				.andExpect(status().isForbidden());
		mvc.perform(addNote(staff, "Should never land."))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("kitchen staff are refused too — the reading is the danger, not the writing")
	void kitchenStaffAreRefusedBothDoors() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		signInAs("uid-conduct-cook", "Line Cook", "conduct-cook@example.com",
				"+919876520012", "KITCHEN_STAFF");

		mvc.perform(authed(get("/api/v1/staff/members/{id}/conduct-notes", staff)))
				.andExpect(status().isForbidden());
		mvc.perform(addNote(staff, "Should never land."))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a conduct note never reaches the schedule's view of a person")
	void notesStayOutOfTheRosterView() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		signInAs("uid-conduct-manager2", "Kitchen Manager", "conduct-manager2@example.com",
				"+919876520013", "KITCHEN_MANAGER");

		String body = mvc.perform(authed(get("/api/v1/staff/profiles/{id}", staff)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("the profile view is shared with the roster, which a manager holds")
				.doesNotContain(PRIVATE_REMARK);
	}

	// ---- The separation from the cross-temple ban (V65, E9-S2) -----------

	@Test
	@DisplayName("conduct notes and the cross-temple ban are not joined, in either direction")
	void conductNotesAndBansAreStructurallyUnconnected() {
		// The durable half of the separation. Prose in a header is guidance; a foreign key is what a
		// future change would actually reach for, and this fails the moment somebody adds one.
		List<Map<String, Object>> joins = admin.queryForList("""
				SELECT c.conname, src.relname AS from_table, tgt.relname AS to_table
				FROM pg_constraint c
				JOIN pg_class src ON src.oid = c.conrelid
				JOIN pg_class tgt ON tgt.oid = c.confrelid
				WHERE c.contype = 'f'
				  AND ((src.relname = 'staff_conduct_notes' AND tgt.relname = 'employment_bans')
				    OR (src.relname = 'employment_bans' AND tgt.relname = 'staff_conduct_notes'))
				""");

		assertThat(joins)
				.as("a conduct note must not become an input to a ban, and a ban must not surface "
						+ "conduct notes — connecting them is its own decision, not a side effect")
				.isEmpty();

		// And neither table holds a column pointing at the other by name, which is how such a link
		// usually arrives first: a bare uuid column, wired up in Java, with no constraint behind it.
		assertThat(columnsOf("staff_conduct_notes"))
				.as("nothing on a conduct note refers to a ban")
				.noneMatch(column -> column.contains("ban"));
		assertThat(columnsOf("employment_bans"))
				.as("nothing on a ban refers to a conduct note")
				.noneMatch(column -> column.contains("conduct") || column.contains("note"));
	}

	@Test
	@DisplayName("a ban raised at a dismissal carries the admin's own words, never a conduct note")
	void aBanNeverPicksUpAConductNote() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		mvc.perform(addNote(staff, PRIVATE_REMARK)).andExpect(status().isCreated());

		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", staff))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"status":"TERMINATED","lastWorkingDay":"2026-08-30",
								 "reason":"Dismissed for cause.","revokeSignIn":true,
								 "ban":{"category":"THEFT","account":"Took cash from the donation box on the 12th."}}
								"""))
				.andExpect(status().isNoContent());

		// The whole ban row, every column, as text. The note's words appear in none of it.
		String banRow = admin.queryForObject(
				"SELECT to_jsonb(b)::text FROM employment_bans b", String.class);

		assertThat(banRow)
				.as("a ban is raised from what the administrator wrote at the dismissal and stands "
						+ "behind — never assembled out of remarks written earlier for another purpose")
				.doesNotContain(PRIVATE_REMARK)
				.doesNotContain("Arrived late")
				.contains("Took cash from the donation box");

		// And the note is still exactly where it was, unchanged by the dismissal.
		mvc.perform(authed(get("/api/v1/staff/members/{id}/conduct-notes", staff)))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].body").value(PRIVATE_REMARK));
	}

	// ---------------------------------------------------------------------

	private List<String> columnsOf(String table) {
		return admin.queryForList("""
				SELECT attname FROM pg_attribute
				WHERE attrelid = format('public.%I', ?)::regclass AND attnum > 0 AND NOT attisdropped
				""", String.class, table);
	}

	/** Runs a statement as the unprivileged application role, scoped to this test's tenant. */
	private void asApplication(java.util.function.Consumer<JdbcTemplate> work) {
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		TenantContext.set(tenant);
		try {
			work.accept(new JdbcTemplate(new TenantAwareDataSource(plain)));
		} finally {
			TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder addNote(String staffId, String body) throws Exception {
		return authed(post("/api/v1/staff/members/{id}/conduct-notes", staffId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(Map.of("body", body)));
	}

	private String hire(String name, String jobTitle) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"fullName":"%s","jobTitle":"%s","employmentType":"FULL_TIME",
								 "dateOfJoining":"2026-02-01"}
								""".formatted(name, jobTitle)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void insertUser(String uid, String name, String email, String phone, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", tenant, uid, name, email, phone, role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void signInAs(String uid, String name, String email, String phone, String role) {
		insertUser(uid, name, email, phone, role);
		signIn(uid);
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
