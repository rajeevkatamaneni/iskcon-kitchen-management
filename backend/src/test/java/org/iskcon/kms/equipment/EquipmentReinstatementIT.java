package org.iskcon.kms.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
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
 * Bringing a scrapped machine back (D-15), through the full stack against a real PostgreSQL.
 *
 * <p>What is asserted here is a pair of properties that only make sense together, and the second is
 * the one this test exists to defend. The first: a scrapped item can be reinstated by name, once
 * asked for deliberately, and comes back onto the default register with a state-change row that says
 * where it came from and why. The second: <strong>terminality is not loosened by any of it</strong>
 * — {@code POST /{id}/condition} on a scrapped item still returns KMS-400043, unconditionally. A
 * future change that made the ordinary condition path accept a scrapped item would satisfy every
 * feature test in this class and still be wrong, so the refusal is asserted here beside the way back.
 *
 * <p>The permission is asserted by name too, and against the two roles most likely to be assumed to
 * hold it: kitchen staff and kitchen managers both have {@code MANAGE_INVENTORY} and can therefore
 * scrap a machine, and neither may bring one back. That asymmetry is the entire point of a separate
 * permission, and it would be invisible in a test that only exercised the Temple Admin.
 */
@AutoConfigureMockMvc
@Import(EquipmentReinstatementIT.StubVerifierConfiguration.class)
class EquipmentReinstatementIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-manager-a", "manager-a@example.com", "KITCHEN_MANAGER");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM equipment_state_changes");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a scrapped machine comes back in the condition it is given, and back onto the register")
	void reinstatementBringsItBack() throws Exception {
		UUID id = create("{\"name\":\"Wet Grinder, 10 litre\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Motor burnt out"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/equipment"))).andExpect(jsonPath("$.length()").value(0));

		mvc.perform(reinstate(id, "NEEDS_REPAIR", "No replacement to be had; rewinding the motor"))
				.andExpect(status().isNoContent());

		// Back in the default list — the register is the test of whether it is in use again, not
		// the item page, because the register is where somebody planning a meal would look for it.
		mvc.perform(authed(get("/api/v1/equipment")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].condition").value("NEEDS_REPAIR"));

		mvc.perform(authed(get("/api/v1/equipment/{id}", id)))
				.andExpect(jsonPath("$.equipment.condition").value("NEEDS_REPAIR"))
				.andExpect(jsonPath("$.history.length()").value(3))
				.andExpect(jsonPath("$.history[0].fromCondition").value("SCRAPPED"))
				.andExpect(jsonPath("$.history[0].toCondition").value("NEEDS_REPAIR"))
				.andExpect(jsonPath("$.history[0].reason")
						.value("No replacement to be had; rewinding the motor"))
				.andExpect(jsonPath("$.history[0].actorName").value("Test Person"));
	}

	@Test
	@DisplayName("the reinstatement is written into the same trail, from SCRAPPED, with its reason")
	void reinstatementIsOneStateChangeRowFromScrapped() throws Exception {
		UUID id = create("{\"name\":\"Steam Boiler\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Written off after the flood"))
				.andExpect(status().isNoContent());
		mvc.perform(reinstate(id, "GOOD", "It dried out and runs; nothing to replace it with"))
				.andExpect(status().isNoContent());

		// Read with the admin connection so what is asserted is the row on disk rather than the
		// shape the API happens to render it in.
		List<Map<String, Object>> rows = admin.queryForList("""
				SELECT from_condition, to_condition, reason
				FROM equipment_state_changes WHERE equipment_id = ?
				ORDER BY created_at DESC, id DESC
				""", id);

		assertThat(rows).hasSize(3);
		assertThat(rows.get(0))
				.containsEntry("from_condition", "SCRAPPED")
				.containsEntry("to_condition", "GOOD")
				.containsEntry("reason", "It dried out and runs; nothing to replace it with");
	}

	@Test
	@DisplayName("it audits as EQUIPMENT_REINSTATED and never as an ordinary condition change")
	void auditsUnderItsOwnAction() throws Exception {
		UUID id = create("{\"name\":\"Tilting Bratt Pan\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Beyond repair")).andExpect(status().isNoContent());
		mvc.perform(reinstate(id, "IN_REPAIR", "Found an engineer who will take it on"))
				.andExpect(status().isNoContent());

		// One condition change (the scrapping) and one reinstatement, filed apart. Filing the
		// second under EQUIPMENT_CONDITION_CHANGED would leave it visible only to somebody already
		// looking for it, which is the opposite of what was asked for.
		assertThat(auditCount("EQUIPMENT_REINSTATED")).isEqualTo(1);
		assertThat(auditCount("EQUIPMENT_CONDITION_CHANGED")).isEqualTo(1);

		assertThat(admin.queryForObject("""
				SELECT reason FROM audit_events WHERE action = 'EQUIPMENT_REINSTATED'
				""", String.class))
				.isEqualTo("Found an engineer who will take it on");
	}

	@Test
	@DisplayName("the ordinary condition path still refuses a scrapped item — terminality is unchanged")
	void conditionChangeStillRefusedAfterScrapping() throws Exception {
		UUID id = create("{\"name\":\"Old Mixer\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Motor burnt out")).andExpect(status().isNoContent());

		mvc.perform(changeCondition(id, "GOOD", "changed my mind"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400043"));

		// And again after a reinstatement and a second scrapping: the guard is a property of the
		// item's state, not a one-shot that a round trip through reinstate could use up.
		mvc.perform(reinstate(id, "GOOD", "Still needed")).andExpect(status().isNoContent());
		mvc.perform(changeCondition(id, "SCRAPPED", "Second time, and final"))
				.andExpect(status().isNoContent());
		mvc.perform(changeCondition(id, "NEEDS_REPAIR", "no"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400043"));
	}

	@Test
	@DisplayName("an item that is not scrapped has nothing to reinstate")
	void notScrappedIsRefused() throws Exception {
		UUID id = create("{\"name\":\"Ladle\"}");

		mvc.perform(reinstate(id, "GOOD", "Nothing happened to it"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400124"));

		mvc.perform(changeCondition(id, "NEEDS_REPAIR", "Handle cracked"))
				.andExpect(status().isNoContent());
		mvc.perform(reinstate(id, "GOOD", "It is only cracked"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400124"));

		assertThat(auditCount("EQUIPMENT_REINSTATED")).isZero();
	}

	@Test
	@DisplayName("coming back as scrapped is not a reinstatement")
	void reinstatingToScrappedIsRefused() throws Exception {
		UUID id = create("{\"name\":\"Dough Kneader\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Cracked bowl")).andExpect(status().isNoContent());

		mvc.perform(reinstate(id, "SCRAPPED", "still scrapped"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		mvc.perform(authed(get("/api/v1/equipment/{id}", id)))
				.andExpect(jsonPath("$.equipment.condition").value("SCRAPPED"))
				.andExpect(jsonPath("$.history.length()").value(2));
	}

	@Test
	@DisplayName("a reinstatement with no reason is rejected")
	void reasonIsRequired() throws Exception {
		UUID id = create("{\"name\":\"Idli Steamer\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Rusted through")).andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/equipment/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"condition\":\"GOOD\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		mvc.perform(reinstate(id, "GOOD", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("kitchen staff and kitchen managers may scrap a machine but may not bring one back")
	void onlyTheTempleAdminMayReinstate() throws Exception {
		UUID id = create("{\"name\":\"Chapati Press\"}");

		// Both roles hold MANAGE_INVENTORY, so both can put it beyond use...
		signIn("uid-staff-a");
		mvc.perform(changeCondition(id, "SCRAPPED", "Plate warped")).andExpect(status().isNoContent());

		// ...and neither may take that back. This is the asymmetry the separate permission exists
		// for, and it is invisible in any test that only signs in as the Temple Admin.
		mvc.perform(reinstate(id, "GOOD", "We cannot replace it"))
				.andExpect(status().isForbidden());

		signIn("uid-manager-a");
		mvc.perform(reinstate(id, "GOOD", "We cannot replace it"))
				.andExpect(status().isForbidden());

		signIn("uid-admin-a");
		mvc.perform(reinstate(id, "GOOD", "We cannot replace it"))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("another temple's scrapped machine is not found")
	void rlsScopesToTenant() throws Exception {
		UUID templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		UUID foreign = admin.queryForObject("""
				INSERT INTO equipment_items (tenant_id, name, condition)
				VALUES (?, 'Foreign Oven', 'SCRAPPED')
				RETURNING id
				""", UUID.class, templeB);

		mvc.perform(reinstate(foreign, "GOOD", "Not ours to bring back"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
	}

	// ---------------------------------------------------------------------

	private UUID create(String json) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/equipment"))
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder changeCondition(UUID id, String condition, String reason) {
		return authed(post("/api/v1/equipment/{id}/condition", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"condition\":\"" + condition + "\",\"reason\":\"" + reason + "\"}");
	}

	private MockHttpServletRequestBuilder reinstate(UUID id, String condition, String reason) {
		return authed(post("/api/v1/equipment/{id}/reinstate", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"condition\":\"" + condition + "\",\"reason\":\"" + reason + "\"}");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
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
