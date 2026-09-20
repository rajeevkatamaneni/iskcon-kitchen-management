package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The catalogue half of "the temple never buys this" (T-402, Rajeev 2026-09-19).
 *
 * <p>{@code NotBoughtShoppingListIT} owns the behaviour — no water line, and stock and costing
 * unchanged. This class owns the flag itself: how it is set, who may set it, what an old row reads
 * as, and the one thing it deliberately cannot ride on.
 *
 * <p>Deliberately not asserted here, for the same reason {@code SupplyIngredientIT} gives about V99:
 * "the backfill ran per tenant under RLS". V153 adds the column {@code NOT NULL DEFAULT false},
 * which is DDL run as the table owner; PostgreSQL fills every existing row itself without a row
 * policy ever being consulted, so there is no backfill statement and no per-tenant loop to test.
 * What is asserted instead, in {@link #everyExistingIngredientIsBought}, is the fact that criterion
 * reaches for: rows written before the column existed read as bought, including a row in a tenant
 * nobody adopted.
 */
@AutoConfigureMockMvc
class NotBoughtIngredientIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-manager-a", "manager-a@example.com", "KITCHEN_MANAGER");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("an admin creates an ingredient already marked, and the list carries the flag")
	void createsAMarkedIngredient() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Water\",\"category\":\"Basics\",\"unit\":\"L\","
						+ "\"ekadashiProhibited\":false,\"supply\":false,\"notBought\":true}"))
				.andExpect(status().isCreated());
		mvc.perform(createRequest("{\"name\":\"Rice\",\"category\":\"Grains\",\"unit\":\"KG\","
						+ "\"ekadashiProhibited\":false,\"supply\":false,\"notBought\":false}"))
				.andExpect(status().isCreated());

		// Both are in the catalogue and both are in the list every picker loads. The flag says what
		// the shopping list does with the row, not whether the row exists.
		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.name=='Water')].notBought").value(true))
				.andExpect(jsonPath("$[?(@.name=='Rice')].notBought").value(false));

		assertThat(flagOf(idOf("Water"))).isTrue();
		// Created marked is still a decision somebody made, so it is on the trail under the action
		// somebody would grep for.
		assertThat(auditCount("INGREDIENT_NOT_BOUGHT_CHANGED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a kitchen manager may add an ingredient but not one that is marked")
	void creatingAMarkedIngredientNeedsThePermission() throws Exception {
		// The point of contrast: MANAGE_RECIPES lets them add ingredients all day. The refusal below
		// is about the flag, not about the call.
		signIn("uid-manager-a");
		mvc.perform(createRequest("{\"name\":\"Rice\",\"category\":\"Grains\",\"unit\":\"KG\","
						+ "\"notBought\":false}"))
				.andExpect(status().isCreated());

		mvc.perform(createRequest("{\"name\":\"Water\",\"category\":\"Basics\",\"unit\":\"L\","
						+ "\"notBought\":true}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400021"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE name = 'Water'", Integer.class))
				.as("nothing was written before the refusal")
				.isZero();
	}

	@Test
	@DisplayName("the flag does not ride on PUT, so the supplies screen's edit row cannot un-set it")
	void anOrdinaryEditCannotClearTheFlag() throws Exception {
		/*
		  The reason the flag has a route of its own, asserted rather than trusted.

		  `frontend/app/supplies/page.tsx` has an editing row that sends a whole update payload built
		  from the fields it knows about. The body below is exactly that shape — name, category, unit,
		  aliases, and nothing this flag would recognise — and it is the payload that would have
		  un-set the mark had the flag joined `UpdateIngredientRequest`. The Ekadashi flag survives
		  the same body only because its field there is a boxed `Boolean` whose null means "leave
		  alone", which took T-121 to get right.

		  If this test ever turns red, somebody has added `notBought` to the PUT and the reasoning in
		  `SetNotBoughtRequest` needs rereading before the change is accepted.
		*/
		UUID water = insertIngredient(templeA, "Water", "Basics", true);

		mvc.perform(authed(put("/api/v1/ingredients/{id}", water))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Water\",\"category\":\"Basics\",\"unit\":\"KG\","
								+ "\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(flagOf(water))
				.as("an ordinary edit says nothing about buying policy and must change nothing")
				.isTrue();
		// And the edit itself went through, so what is asserted above is the flag surviving a real
		// change rather than a refused request.
		assertThat(admin.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, water))
				.isEqualTo("KG");
		// The audit snapshot still states the flag, on both sides, so a reader is never left to
		// infer that an absent key meant "unchanged".
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM audit_events
				WHERE action = 'INGREDIENT_UPDATED'
				  AND before_state ->> 'notBought' = 'true' AND after_state ->> 'notBought' = 'true'
				""", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("the mark goes on and comes off, and each move is audited")
	void theFlagIsSetAndClearedAndAudited() throws Exception {
		UUID water = insertIngredient(templeA, "Water", "Basics", false);

		mvc.perform(notBoughtRequest(water, true)).andExpect(status().isNoContent());
		assertThat(flagOf(water)).isTrue();

		mvc.perform(notBoughtRequest(water, false)).andExpect(status().isNoContent());
		assertThat(flagOf(water)).isFalse();

		assertThat(auditCount("INGREDIENT_NOT_BOUGHT_CHANGED")).isEqualTo(2);

		// Setting it to what it already is writes nothing and audits nothing, so a screen re-sending
		// what it is showing does not fill the trail with moves nobody made.
		mvc.perform(notBoughtRequest(water, false)).andExpect(status().isNoContent());
		assertThat(auditCount("INGREDIENT_NOT_BOUGHT_CHANGED")).isEqualTo(2);
	}

	@Test
	@DisplayName("setting the flag is not a review, so an imported row keeps its label")
	void theFlagDoesNotClearTheImportLabel() throws Exception {
		// The same rule setEkadashiFlag follows: writing one flag happens without ever showing
		// anybody the category and unit an import guessed, which is the thing the mark asks to have
		// looked at.
		UUID water = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, library_derived)
				VALUES (?, 'Water', 'Basics', 'L', true) RETURNING id
				""", UUID.class, templeA);

		mvc.perform(notBoughtRequest(water, true)).andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, water)).isTrue();
	}

	@Test
	@DisplayName("every ingredient that existed before the column does reads as bought, in every tenant")
	void everyExistingIngredientIsBought() throws Exception {
		// Temple B's row is here on purpose. It belongs to a tenant nobody signs in as, so nothing in
		// this test ever adopts its tenant context; if the column had been filled by DML under RLS
		// instead of by DDL, a row like this is precisely the one that would have been missed.
		UUID riceA = insertIngredientWithoutTheColumn(templeA, "Rice", "Grains");
		UUID payasamB = insertIngredientWithoutTheColumn(templeB, "Payasam Base", "Sweets");

		assertThat(flagOf(riceA)).isFalse();
		assertThat(flagOf(payasamB)).isFalse();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE is_not_bought", Integer.class)).isZero();

		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(jsonPath("$[?(@.name=='Rice')].notBought").value(false));
	}

	@Test
	@DisplayName("another temple's ingredient is simply not found, so the flag cannot cross a tenant")
	void theFlagIsConfinedByRls() throws Exception {
		// No policy was added for this column and none was needed: `ingredients` has called
		// enable_tenant_rls() since V10 and a policy is a row predicate, not a column list. This is
		// that inheritance, over HTTP.
		UUID otherTemplesWater = insertIngredient(templeB, "Water", "Basics", false);

		mvc.perform(notBoughtRequest(otherTemplesWater, true))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));

		assertThat(flagOf(otherTemplesWater)).isFalse();
	}

	// ---------------------------------------------------------------------

	private Boolean flagOf(UUID id) {
		return admin.queryForObject("SELECT is_not_bought FROM ingredients WHERE id = ?", Boolean.class, id);
	}

	private UUID idOf(String name) {
		return admin.queryForObject("SELECT id FROM ingredients WHERE name = ?", UUID.class, name);
	}

	private UUID insertIngredient(UUID tenantId, String name, String category, boolean notBought) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_not_bought)
				VALUES (?, ?, ?, 'KG', ?) RETURNING id
				""", UUID.class, tenantId, name, category, notBought);
	}

	/** An insert that never names the column, which is how every row older than V153 was written. */
	private UUID insertIngredientWithoutTheColumn(UUID tenantId, String name, String category) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, 'KG') RETURNING id
				""", UUID.class, tenantId, name, category);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/ingredients")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder notBoughtRequest(UUID id, boolean notBought) {
		return authed(patch("/api/v1/ingredients/{id}/not-bought", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"notBought\":" + notBought + "}");
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
}
