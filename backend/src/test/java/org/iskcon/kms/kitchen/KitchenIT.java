package org.iskcon.kms.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The kitchens register (E10-S2) through the full stack: RLS, the cross-tenant reference the
 * foreign key would let through, the single main kitchen the database enforces, and the split
 * between deleting a kitchen and archiving one.
 */
@AutoConfigureMockMvc
@Import(KitchenIT.StubVerifierConfiguration.class)
class KitchenIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID adminA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		adminA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeB, "uid-admin-b", "admin-b@example.com", "TEMPLE_ADMIN");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a kitchen is created, listed, fetched, and the creation is audited")
	void createsListsAndGets() throws Exception {
		String id = createKitchen("Deity kitchen");

		mvc.perform(authed(get("/api/v1/kitchens")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Deity kitchen')]").exists());

		mvc.perform(authed(get("/api/v1/kitchens/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Deity kitchen"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.usesMealPlanner").value(false));

		assertThat(auditCount("KITCHEN_CREATED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a temple's first kitchen is its main one whatever the form said")
	void firstKitchenIsMain() throws Exception {
		// The form did not tick it, and it is main regardless: there is no other kitchen for the
		// flag to sit on.
		String id = createKitchen("Prasadam kitchen");

		mvc.perform(authed(get("/api/v1/kitchens/{id}", id)))
				.andExpect(jsonPath("$.isMain").value(true));
	}

	@Test
	@DisplayName("marking a second kitchen main takes the flag off the first, in one act")
	void markingASecondMainClearsTheFirst() throws Exception {
		String first = createKitchen("Deity kitchen");
		String second = createKitchen("Restaurant", true);

		mvc.perform(authed(get("/api/v1/kitchens/{id}", first)))
				.andExpect(jsonPath("$.isMain").value(false));
		mvc.perform(authed(get("/api/v1/kitchens/{id}", second)))
				.andExpect(jsonPath("$.isMain").value(true));

		assertThat(mainKitchenCount(templeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("editing a kitchen to be the main one moves the flag off whichever holds it")
	void editingToMainMovesTheFlag() throws Exception {
		String first = createKitchen("Deity kitchen");
		String second = createKitchen("Guest house kitchen");

		mvc.perform(authed(put("/api/v1/kitchens/{id}", second))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Guest house kitchen\",\"isMain\":true,\"usesMealPlanner\":false}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/kitchens/{id}", first)))
				.andExpect(jsonPath("$.isMain").value(false));
		mvc.perform(authed(get("/api/v1/kitchens/{id}", second)))
				.andExpect(jsonPath("$.isMain").value(true));
		assertThat(mainKitchenCount(templeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("the database itself refuses a temple two main kitchens")
	void theDatabaseRefusesTwoMains() throws Exception {
		createKitchen("Deity kitchen");
		createKitchen("Restaurant");

		// Straight at the table, going round the service entirely — because the guarantee has to be
		// the database's and not the application's. A second main is refused by
		// kitchens_one_main_per_tenant, which no amount of application code can talk round.
		assertThatThrownBy(() -> admin.update(
				"UPDATE kitchens SET is_main = true WHERE tenant_id = ? AND name = 'Restaurant'", templeA))
				.hasMessageContaining("kitchens_one_main_per_tenant");

		assertThat(mainKitchenCount(templeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("the same name twice in one temple is refused, and the same name elsewhere is not")
	void refusesADuplicateName() throws Exception {
		createKitchen("Deity kitchen");

		mvc.perform(createRequest(body("Deity kitchen", false, false, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4972"));

		// The other temple's Deity kitchen is its own business.
		signIn("uid-admin-b");
		mvc.perform(createRequest(body("Deity kitchen", false, false, null)))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a kitchen belonging to another temple is invisible and unreachable")
	void anotherTemplesKitchenIsInvisible() throws Exception {
		signIn("uid-admin-b");
		String theirs = createKitchen("Their kitchen");

		signIn("uid-admin-a");
		mvc.perform(authed(get("/api/v1/kitchens")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Their kitchen')]").doesNotExist());
		mvc.perform(authed(get("/api/v1/kitchens/{id}", theirs)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4974"));
	}

	@Test
	@DisplayName("a kitchen cannot be put in the charge of somebody from another temple")
	void refusesACrossTenantPersonInCharge() throws Exception {
		UUID theirAdmin = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin-b'", UUID.class);

		// The foreign key would take it — FK checks run as the table owner and bypass RLS — so the
		// service looks the person up through RLS first and refuses an id it cannot see.
		mvc.perform(createRequest(body("Sneaky kitchen", false, false, theirAdmin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("a kitchen from this temple can be put in somebody's charge, and the list names them")
	void namesThePersonInCharge() throws Exception {
		mvc.perform(createRequest(body("Deity kitchen", false, false, adminA)))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/kitchens")))
				.andExpect(jsonPath("$[0].inChargeName").value("Test Person"));
	}

	@Test
	@DisplayName("a kitchen nothing has asked the store through is deleted outright")
	void unreferencedKitchenIsDeleted() throws Exception {
		String id = createKitchen("Deity kitchen");

		mvc.perform(authed(delete("/api/v1/kitchens/{id}", id))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/kitchens").param("includeArchived", "true")))
				.andExpect(jsonPath("$[?(@.name=='Deity kitchen')]").doesNotExist());
		mvc.perform(authed(get("/api/v1/kitchens/{id}", id))).andExpect(status().isNotFound());
		assertThat(auditCount("KITCHEN_DELETED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a kitchen that has asked for ingredients refuses deletion and says to archive it")
	void referencedKitchenCannotBeDeleted() throws Exception {
		String id = createKitchen("Deity kitchen");
		raiseARequestFor(id);

		mvc.perform(authed(delete("/api/v1/kitchens/{id}", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4973"));

		// And archiving, which is what the refusal told the user to do, works.
		mvc.perform(authed(post("/api/v1/kitchens/{id}/archive", id))).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/kitchens/{id}", id)))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	@DisplayName("archiving hides a kitchen from the list, and restoring brings it back")
	void archiveAndRestore() throws Exception {
		String id = createKitchen("Food for Life kitchen");

		mvc.perform(authed(post("/api/v1/kitchens/{id}/archive", id))).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/kitchens")))
				.andExpect(jsonPath("$[?(@.name=='Food for Life kitchen')]").doesNotExist());
		mvc.perform(authed(get("/api/v1/kitchens").param("includeArchived", "true")))
				.andExpect(jsonPath("$[?(@.name=='Food for Life kitchen')]").exists());

		mvc.perform(authed(post("/api/v1/kitchens/{id}/restore", id))).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/kitchens")))
				.andExpect(jsonPath("$[?(@.name=='Food for Life kitchen')]").exists());

		assertThat(auditCount("KITCHEN_ARCHIVED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an archived kitchen cannot be edited until it is restored")
	void archivedKitchenCannotBeEdited() throws Exception {
		String id = createKitchen("Food for Life kitchen");
		mvc.perform(authed(post("/api/v1/kitchens/{id}/archive", id))).andExpect(status().isNoContent());

		mvc.perform(authed(put("/api/v1/kitchens/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Renamed\",\"isMain\":false,\"usesMealPlanner\":false}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4975"));
	}

	@Test
	@DisplayName("the meal-planner flag is settable and is only stored, cascade or no cascade")
	void mealPlannerFlagIsPersisted() throws Exception {
		String id = createKitchen("Deity kitchen");

		mvc.perform(authed(put("/api/v1/kitchens/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Deity kitchen\",\"isMain\":true,\"usesMealPlanner\":true}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/kitchens/{id}", id)))
				.andExpect(jsonPath("$.usesMealPlanner").value(true));
		assertThat(auditCount("KITCHEN_UPDATED")).isEqualTo(1);
	}

	@Test
	@DisplayName("kitchen staff may read the kitchens list but not add one")
	void staffMayReadButNotWrite() throws Exception {
		createKitchen("Deity kitchen");

		signIn("uid-staff-a");

		// Reading rides on REQUEST_INGREDIENTS: you cannot raise a request without choosing one.
		mvc.perform(authed(get("/api/v1/kitchens")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Deity kitchen')]").exists());

		mvc.perform(createRequest(body("Sneaky kitchen", false, false, null)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));
	}

	// ---------------------------------------------------------------------

	/**
	 * Puts a request against the kitchen, which is the only thing that makes deleting it wrong. It
	 * is written straight to the table rather than through E10-S5's endpoints, because the point
	 * here is the reference, not the raising of it.
	 */
	private void raiseARequestFor(String kitchenId) {
		admin.update("""
				INSERT INTO ingredient_requests (tenant_id, reference, kitchen_id, needed_on, requested_by)
				VALUES (?, 'IR-2026-0001', ?::uuid, CURRENT_DATE,
					(SELECT id FROM users WHERE firebase_uid = 'uid-admin-a'))
				""", templeA, kitchenId);
	}

	private String createKitchen(String name) throws Exception {
		return createKitchen(name, false);
	}

	private String createKitchen(String name, boolean isMain) throws Exception {
		String response = mvc.perform(createRequest(body(name, isMain, false, null)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private String body(String name, boolean isMain, boolean usesMealPlanner, UUID inChargeUserId) {
		String inCharge = inChargeUserId == null ? "null" : "\"" + inChargeUserId + "\"";
		return ("{\"name\":\"%s\",\"description\":\"Cooks for the Deities.\","
				+ "\"location\":\"Behind the Deity hall\",\"isMain\":%s,\"usesMealPlanner\":%s,"
				+ "\"inChargeUserId\":%s,\"contactPhone\":\"204\"}")
				.formatted(name, isMain, usesMealPlanner, inCharge);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/kitchens")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private int mainKitchenCount(UUID tenantId) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM kitchens WHERE tenant_id = ? AND is_main", Integer.class, tenantId);
		return c == null ? 0 : c;
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, email, role);
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
