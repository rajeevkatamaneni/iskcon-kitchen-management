package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every staff member belongs to exactly one kitchen (Epic 12, T-357), through the full stack.
 *
 * <p>Rajeev, 2026-09-19: "every staff member belongs to exactly one kitchen — required on add and edit
 * … existing staff are put in the main kitchen … and the Temple Admin gets a 'check these kitchen
 * assignments' list." What is proved here:
 * <ul>
 *   <li>A hire and an edit without a kitchen are refused with {@code KMS-400184}; an archived kitchen
 *       with {@code KMS-400109}; one this temple does not have — another temple's included, which only
 *       the row policy stops — with {@code KMS-400108}. A kitchen that does not plan its meals here is
 *       allowed.
 *   <li>Saving the record clears {@code kitchen_needs_check}, and the audit entry names the kitchen as
 *       the row holds it.
 *   <li>The check list shows only flagged, current staff of this temple; changing a kitchen from it
 *       and confirming it both clear the flag.
 *   <li>Only {@code MANAGE_STAFF} may call the three new endpoints, and a temple can neither see nor
 *       change another temple's records through them.
 * </ul>
 *
 * <p>Same context as {@code StaffEmploymentIT} (MockMvc and a mocked {@link Scheduler}), so the two
 * share one cached application context rather than starting a second.
 */
@AutoConfigureMockMvc
class StaffKitchenIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID mainKitchen;
	private UUID storeKitchen;
	private UUID closedKitchen;
	private UUID kitchenB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = temple("staff-kitchen-a", "Bengaluru Temple");
		templeB = temple("staff-kitchen-b", "Mysuru Temple");
		UUID adminA = user(templeA, "uid-sk-admin-a", "sk-admin-a@example.com", "+919876530001", "TEMPLE_ADMIN");
		user(templeA, "uid-sk-manager-a", "sk-manager-a@example.com", "+919876530002", "KITCHEN_MANAGER");
		user(templeA, "uid-sk-cook-a", "sk-cook-a@example.com", "+919876530003", "KITCHEN_STAFF");
		UUID adminB = user(templeB, "uid-sk-admin-b", "sk-admin-b@example.com", "+919876530004", "TEMPLE_ADMIN");

		mainKitchen = kitchen(templeA, "Main kitchen", true, true, "ACTIVE", adminA);
		// A kitchen that only draws from the store. People work there too; it just is not the planner.
		storeKitchen = kitchen(templeA, "Store kitchen", false, false, "ACTIVE", adminA);
		closedKitchen = kitchen(templeA, "Old kitchen", false, true, "ARCHIVED", adminA);
		kitchenB = kitchen(templeB, "Main kitchen", true, true, "ACTIVE", adminB);
		signIn("uid-sk-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Hiring and editing ---------------------------------------------------

	@Test
	@DisplayName("a hire without a kitchen is refused, and before the ban check has run")
	void hireNeedsAKitchen() throws Exception {
		mvc.perform(hire(null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400184"));

		assertThat(count("SELECT count(*) FROM staff_profiles")).isZero();
		assertThat(count("SELECT count(*) FROM platform_audit_events WHERE action = 'BAN_CHECK_RUN'"))
				.as("a hire that could never complete must not leave a ban check on the platform log")
				.isZero();
	}

	@Test
	@DisplayName("a hire into a kitchen that does not plan its meals here is allowed, and nothing is flagged")
	void hireIntoAStoreKitchen() throws Exception {
		String id = hireId(storeKitchen);

		assertThat(admin.queryForObject(
				"SELECT kitchen_id FROM staff_profiles WHERE id = ?::uuid", UUID.class, id)).isEqualTo(storeKitchen);
		assertThat(admin.queryForObject(
				"SELECT kitchen_needs_check FROM staff_profiles WHERE id = ?::uuid", Boolean.class, id))
				.as("the admin chose it on the form, so it is not a guess to be checked")
				.isFalse();

		mvc.perform(authed(get("/api/v1/staff/register")))
				.andExpect(jsonPath("$.current[0].kitchenId").value(storeKitchen.toString()))
				.andExpect(jsonPath("$.current[0].kitchenName").value("Store kitchen"))
				.andExpect(jsonPath("$.current[0].kitchenNeedsCheck").value(false));

		JsonNode after = JSON.readTree(admin.queryForObject(
				"SELECT after_state::text FROM audit_events WHERE action = 'STAFF_HIRED'", String.class));
		assertThat(after.get("kitchenId").asText()).isEqualTo(storeKitchen.toString());
		assertThat(after.get("kitchenName").asText()).isEqualTo("Store kitchen");
	}

	@Test
	@DisplayName("an archived kitchen, an unknown one and another temple's are each refused with their own code")
	void unusableKitchensAreRefused() throws Exception {
		mvc.perform(hire(closedKitchen))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400109"));
		mvc.perform(hire(UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400108"));
		// The foreign key would accept this — it is checked as the table owner and knows nothing of
		// temples. Only the row policy on the lookup stops a record here naming a kitchen there.
		mvc.perform(hire(kitchenB))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400108"));

		assertThat(count("SELECT count(*) FROM staff_profiles")).isZero();
	}

	@Test
	@DisplayName("an edit without a kitchen is refused; an edit with one moves them, clears the flag and is audited as stored")
	void editNeedsAKitchenAndClearsTheFlag() throws Exception {
		String id = hireId(mainKitchen);
		admin.update("UPDATE staff_profiles SET kitchen_needs_check = true WHERE id = ?::uuid", id);

		mvc.perform(update(id, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400184"));
		mvc.perform(update(id, closedKitchen))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400109"));
		assertThat(needsCheck(id)).as("a refused edit changes nothing").isTrue();

		mvc.perform(update(id, storeKitchen)).andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT kitchen_id FROM staff_profiles WHERE id = ?::uuid", UUID.class, id)).isEqualTo(storeKitchen);
		assertThat(needsCheck(id)).as("saving the record is the admin looking at its kitchen").isFalse();

		JsonNode before = JSON.readTree(admin.queryForObject(
				"SELECT before_state::text FROM audit_events WHERE action = 'STAFF_UPDATED'", String.class));
		JsonNode after = JSON.readTree(admin.queryForObject(
				"SELECT after_state::text FROM audit_events WHERE action = 'STAFF_UPDATED'", String.class));
		assertThat(before.get("kitchenName").asText()).isEqualTo("Main kitchen");
		assertThat(after.get("kitchenName").asText()).isEqualTo("Store kitchen");
		assertThat(admin.queryForObject(
				"SELECT reason FROM audit_events WHERE action = 'STAFF_UPDATED'", String.class))
				.contains("kitchen Main kitchen → Store kitchen");
	}

	@Test
	@DisplayName("an edit that leaves the kitchen where it is still clears the flag")
	void editInTheSameKitchenClearsTheFlag() throws Exception {
		String id = hireId(mainKitchen);
		admin.update("UPDATE staff_profiles SET kitchen_needs_check = true WHERE id = ?::uuid", id);

		mvc.perform(update(id, mainKitchen)).andExpect(status().isNoContent());

		assertThat(needsCheck(id)).isFalse();
	}

	// ---- The check list --------------------------------------------------------

	@Test
	@DisplayName("the check list shows flagged, current staff of this temple, and nobody else")
	void checkListShowsOnlyFlaggedCurrentStaff() throws Exception {
		UUID flagged = staff(templeA, "Radha Devi", "HEAD_COOK", mainKitchen, true, "ACTIVE");
		staff(templeA, "Already Checked", "COOK", mainKitchen, false, "ACTIVE");
		staff(templeA, "Left Last Year", "COOK", mainKitchen, true, "RESIGNED");
		staff(templeB, "Other Temple", "COOK", kitchenB, true, "ACTIVE");

		mvc.perform(authed(get("/api/v1/staff/kitchen-checks")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].staffId").value(flagged.toString()))
				.andExpect(jsonPath("$[0].fullName").value("Radha Devi"))
				.andExpect(jsonPath("$[0].jobTitleLabel").value("Head Cook"))
				.andExpect(jsonPath("$[0].kitchenId").value(mainKitchen.toString()))
				.andExpect(jsonPath("$[0].kitchenName").value("Main kitchen"));
	}

	@Test
	@DisplayName("changing a kitchen from the list moves them and takes them off it")
	void setKitchenMovesAndClears() throws Exception {
		UUID flagged = staff(templeA, "Radha Devi", "HEAD_COOK", mainKitchen, true, "ACTIVE");

		mvc.perform(setKitchen(flagged, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400184"));
		mvc.perform(setKitchen(flagged, closedKitchen))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400109"));
		mvc.perform(setKitchen(flagged, kitchenB))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400108"));
		assertThat(needsCheck(flagged.toString())).isTrue();

		mvc.perform(setKitchen(flagged, storeKitchen)).andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT kitchen_id FROM staff_profiles WHERE id = ?", UUID.class, flagged)).isEqualTo(storeKitchen);
		assertThat(needsCheck(flagged.toString())).isFalse();
		mvc.perform(authed(get("/api/v1/staff/kitchen-checks"))).andExpect(jsonPath("$.length()").value(0));

		JsonNode after = JSON.readTree(admin.queryForObject(
				"SELECT after_state::text FROM audit_events WHERE action = 'STAFF_UPDATED'", String.class));
		assertThat(after.get("kitchenName").asText()).isEqualTo("Store kitchen");
		assertThat(after.get("kitchenNeedsCheck").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("a former employee's kitchen is not changed from the list")
	void setKitchenRefusesAFormerEmployee() throws Exception {
		UUID former = staff(templeA, "Left Last Year", "COOK", mainKitchen, true, "RESIGNED");

		mvc.perform(setKitchen(former, storeKitchen))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400085"));
	}

	@Test
	@DisplayName("confirming clears the named records' flags and leaves their kitchens and everyone else alone")
	void confirmClearsOnlyTheNamed() throws Exception {
		UUID radha = staff(templeA, "Radha Devi", "HEAD_COOK", mainKitchen, true, "ACTIVE");
		UUID gopal = staff(templeA, "Gopal Das", "COOK", storeKitchen, true, "ACTIVE");
		UUID other = staff(templeA, "Not Yet Seen", "COOK", mainKitchen, true, "ACTIVE");

		mvc.perform(confirm("[\"%s\",\"%s\"]".formatted(radha, gopal))).andExpect(status().isNoContent());

		assertThat(needsCheck(radha.toString())).isFalse();
		assertThat(needsCheck(gopal.toString())).isFalse();
		assertThat(needsCheck(other.toString())).as("not named, not confirmed").isTrue();
		assertThat(admin.queryForObject(
				"SELECT kitchen_id FROM staff_profiles WHERE id = ?", UUID.class, gopal)).isEqualTo(storeKitchen);
		assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'STAFF_UPDATED'")).isEqualTo(2);

		// Confirming again is harmless and writes nothing more.
		mvc.perform(confirm("[\"%s\"]".formatted(radha))).andExpect(status().isNoContent());
		assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'STAFF_UPDATED'")).isEqualTo(2);
	}

	@Test
	@DisplayName("confirming is all or nothing: one unknown id and nothing is marked; an empty list is refused")
	void confirmIsAllOrNothing() throws Exception {
		UUID radha = staff(templeA, "Radha Devi", "HEAD_COOK", mainKitchen, true, "ACTIVE");

		mvc.perform(confirm("[\"%s\",\"%s\"]".formatted(radha, UUID.randomUUID())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
		assertThat(needsCheck(radha.toString())).isTrue();

		mvc.perform(confirm("[]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	// ---- Who may, and which temple ---------------------------------------------

	@Test
	@DisplayName("only MANAGE_STAFF may use the check list: a Kitchen Manager and a cook are refused, even with a bad body")
	void onlyStaffManagementMayCall() throws Exception {
		UUID radha = staff(templeA, "Radha Devi", "HEAD_COOK", mainKitchen, true, "ACTIVE");

		for (String uid : new String[] {"uid-sk-manager-a", "uid-sk-cook-a"}) {
			signIn(uid);
			mvc.perform(authed(get("/api/v1/staff/kitchen-checks")))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("KMS-400021"));
			mvc.perform(setKitchen(radha, storeKitchen))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("KMS-400021"));
			mvc.perform(confirm("[\"%s\"]".formatted(radha)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("KMS-400021"));
			// A body that would fail validation is still refused for the permission, not described.
			mvc.perform(confirm("[]"))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("KMS-400021"));
		}

		assertThat(needsCheck(radha.toString())).isTrue();
		assertThat(admin.queryForObject(
				"SELECT kitchen_id FROM staff_profiles WHERE id = ?", UUID.class, radha)).isEqualTo(mainKitchen);
	}

	@Test
	@DisplayName("another temple's admin can neither see nor change these records")
	void anotherTempleCannotSeeOrChange() throws Exception {
		UUID radha = staff(templeA, "Radha Devi", "HEAD_COOK", mainKitchen, true, "ACTIVE");
		signIn("uid-sk-admin-b");

		mvc.perform(authed(get("/api/v1/staff/kitchen-checks")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(setKitchen(radha, kitchenB))
				.andExpect(status().isNotFound());
		mvc.perform(confirm("[\"%s\"]".formatted(radha)))
				.andExpect(status().isNotFound());

		assertThat(needsCheck(radha.toString())).isTrue();
		assertThat(admin.queryForObject(
				"SELECT kitchen_id FROM staff_profiles WHERE id = ?", UUID.class, radha)).isEqualTo(mainKitchen);
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder hire(UUID kitchen) {
		String kitchenField = kitchen == null ? "" : ",\"kitchenId\":\"" + kitchen + "\"";
		return authed(post("/api/v1/staff/members")).contentType(MediaType.APPLICATION_JSON).content("""
				{"fullName":"Lakshmi Devi","phone":"+919876530061","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-03-01"%s}
				""".formatted(kitchenField));
	}

	private String hireId(UUID kitchen) throws Exception {
		String body = mvc.perform(hire(kitchen)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder update(String id, UUID kitchen) {
		String kitchenField = kitchen == null ? "" : ",\"kitchenId\":\"" + kitchen + "\"";
		return authed(put("/api/v1/staff/members/{id}", id)).contentType(MediaType.APPLICATION_JSON).content("""
				{"fullName":"Lakshmi Devi","phone":"+919876530061","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-03-01"%s}
				""".formatted(kitchenField));
	}

	private MockHttpServletRequestBuilder setKitchen(UUID staffId, UUID kitchen) {
		return authed(put("/api/v1/staff/members/{id}/kitchen", staffId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(kitchen == null ? "{}" : "{\"kitchenId\":\"" + kitchen + "\"}");
	}

	private MockHttpServletRequestBuilder confirm(String idsJsonArray) {
		return authed(post("/api/v1/staff/kitchen-checks/confirm"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"staffIds\":" + idsJsonArray + "}");
	}

	private boolean needsCheck(String staffId) {
		return Boolean.TRUE.equals(admin.queryForObject(
				"SELECT kitchen_needs_check FROM staff_profiles WHERE id = ?::uuid", Boolean.class, staffId));
	}

	private int count(String sql) {
		Integer n = admin.queryForObject(sql, Integer.class);
		return n == null ? 0 : n;
	}

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID temple, String uid, String email, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, temple, uid, email, phone, role);
	}

	private UUID kitchen(UUID temple, String name, boolean main, boolean planner, String status, UUID createdBy) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, ?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, temple, name, main, planner, status, createdBy);
	}

	private UUID staff(UUID temple, String name, String jobTitle, UUID kitchen, boolean needsCheck, String status) {
		return admin.queryForObject("""
				INSERT INTO staff_profiles (tenant_id, full_name, job_title, employment_type, date_of_joining,
					employment_status, last_working_day, kitchen_id, kitchen_needs_check)
				VALUES (?, ?, ?, 'FULL_TIME', DATE '2026-01-01', ?,
					CASE WHEN ? = 'ACTIVE' THEN NULL ELSE DATE '2026-06-30' END, ?, ?)
				RETURNING id
				""", UUID.class, temple, name, jobTitle, status, status, kitchen, needsCheck);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
