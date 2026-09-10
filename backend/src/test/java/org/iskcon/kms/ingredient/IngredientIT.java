package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Ingredient master (E2-S1) through the full stack: RLS scoping, the descriptive-vs-compliance
 * permission split, duplicate handling, and typeahead all really in play.
 */
@AutoConfigureMockMvc
@Import(IngredientIT.StubVerifierConfiguration.class)
class IngredientIT extends AbstractIntegrationTest {

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
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM stock_movements");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("an ingredient is created and listed, and the creation is audited")
	void createsAndLists() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Toor Dal\",\"category\":\"Pulses\",\"unit\":\"KG\","
						+ "\"ekadashiProhibited\":false,\"aliases\":[\"Arhar Dal\"]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").exists());

		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')]").exists());

		assertThat(auditCount("INGREDIENT_ADDED")).isEqualTo(1);
	}

	@Test
	@DisplayName("kitchen staff may add an ordinary ingredient but not mark one prohibited")
	void staffCannotMarkProhibited() throws Exception {
		// Until 2026-09-08 this was written against the sattvic flag, which D-18 deleted. The rule it
		// describes is unchanged and now lives entirely on the Ekadashi flag: adding an ingredient is
		// kitchen work, deciding one is prohibited is a religious-policy call reserved to a Temple
		// Admin (MANAGE_DIETARY_POLICY).
		signIn("uid-staff-a");

		mvc.perform(createRequest("{\"name\":\"Rice\",\"category\":\"Grains\",\"unit\":\"KG\"}"))
				.andExpect(status().isCreated());

		mvc.perform(createRequest("{\"name\":\"Jowar Flour\",\"category\":\"Grains\",\"unit\":\"KG\","
						+ "\"ekadashiProhibited\":true}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400021"));
	}

	@Test
	@DisplayName("only a Temple Admin can change the Ekadashi-prohibited flag, and it is audited")
	void onlyAdminChangesEkadashiFlag() throws Exception {
		// The sattvic-flag version of this test stood here until 2026-09-08. D-18 deleted that flag
		// and its endpoint, so the test is rewritten against the flag that survives rather than
		// dropped: the surviving flag had no endpoint test of its own, and this is the same rule.
		UUID rice = createIngredientAsAdmin("Rice", "Grains", "KG");

		// Kitchen staff is refused the flag endpoint outright.
		signIn("uid-staff-a");
		mvc.perform(ekadashiRequest(rice, true)).andExpect(status().isForbidden());

		// The admin can, and it lands on the audit trail.
		signIn("uid-admin-a");
		mvc.perform(ekadashiRequest(rice, true)).andExpect(status().isNoContent());
		assertThat(admin.queryForObject(
				"SELECT is_ekadashi_prohibited FROM ingredients WHERE id = ?", Boolean.class, rice)).isTrue();
		assertThat(auditCount("INGREDIENT_EKADASHI_FLAG_CHANGED")).isEqualTo(1);
	}

	@Test
	@DisplayName("the sattvic-flag endpoint is gone, not merely inert (D-18)")
	void sattvicFlagEndpointIsGone() throws Exception {
		// The deliberate negative control for the half of D-18 that a passing test cannot show: the
		// route no longer exists, so nothing can put an ingredient back into the state the deleted
		// enforcement used to read. A no-op endpoint left in place would answer 204 and change
		// nothing, which is the outcome this asserts against.
		UUID rice = createIngredientAsAdmin("Rice", "Grains", "KG");

		signIn("uid-admin-a");
		mvc.perform(authed(patch("/api/v1/ingredients/{id}/sattvic-flag", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sattvicProhibited\":true}"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a duplicate name at the same temple is refused with a quotable code")
	void refusesDuplicateName() throws Exception {
		createIngredientAsAdmin("Rice", "Grains", "KG");

		mvc.perform(createRequest("{\"name\":\"rice\",\"category\":\"Grains\",\"unit\":\"KG\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400034"));
	}

	@Test
	@DisplayName("typeahead matches on name and on alias")
	void typeaheadMatchesNameAndAlias() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Toor Dal\",\"category\":\"Pulses\",\"unit\":\"KG\","
				+ "\"aliases\":[\"Arhar Dal\"]}")).andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/ingredients/search").param("q", "toor")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')]").exists());

		mvc.perform(authed(get("/api/v1/ingredients/search").param("q", "arhar")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')]").exists());
	}

	@Test
	@DisplayName("an ingredient in another temple is simply not found")
	void rlsScopesToTenant() throws Exception {
		UUID other = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Payasam Base', 'Sweets', 'L') RETURNING id
				""", UUID.class, templeB);

		mvc.perform(authed(get("/api/v1/ingredients/{id}", other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
	}

	@Test
	@DisplayName("an ingredient with stock history is refused deletion in plain language, not an internal error")
	void refusesDeleteWhenHeldByAnAppendOnlyLedger() throws Exception {
		UUID rice = createIngredientAsAdmin("Rice", "Grains", "KG");
		// A stock movement is append-only: the application role has no DELETE on it, so the FK's own
		// check used to fail as "permission denied" and surfaced as KMS-500001.
		UUID actor = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin-a'", UUID.class);
		admin.update("""
				INSERT INTO stock_movements
					(tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, gen_random_uuid(), 5, 'KG', 'DONATION_IN_KIND', ?)
				""", templeA, rice, actor);

		mvc.perform(authed(delete("/api/v1/ingredients/{id}", rice)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400035"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE id = ?", Integer.class, rice)).isEqualTo(1);
	}

	@Test
	@DisplayName("an unused ingredient is deleted, and the deletion is audited")
	void deletesWhenNothingHoldsIt() throws Exception {
		UUID leek = createIngredientAsAdmin("Sorrel", "Vegetables", "KG");
		// A ledger with rows in it, for a different ingredient: the deployment's ledger is never
		// empty, and an empty one would not exercise the foreign key's own check.
		UUID other = createIngredientAsAdmin("Rice", "Grains", "KG");
		UUID actor2 = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin-a'", UUID.class);
		admin.update("""
				INSERT INTO stock_movements
					(tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, gen_random_uuid(), 5, 'KG', 'DONATION_IN_KIND', ?)
				""", templeA, other, actor2);

		mvc.perform(authed(delete("/api/v1/ingredients/{id}", leek)))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE id = ?", Integer.class, leek)).isZero();
		assertThat(auditCount("INGREDIENT_DELETED")).isEqualTo(1);
	}

	// ------------------------------------------------------------------- T-119

	/*
	 * An ingredient a recipe import created is marked, findable and clearable.
	 *
	 * The column has existed since V69 and nothing read it, so a temple's catalogue filled with
	 * rows nobody chose and there was no way to tell them from the ones somebody set up properly.
	 */

	@Test
	@DisplayName("an ingredient a person typed is never marked as import-created")
	void handTypedIngredientIsNeverMarked() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Toor Dal\",\"category\":\"Pulses\",\"unit\":\"KG\","
						+ "\"ekadashiProhibited\":false,\"aliases\":[]}"))
				.andExpect(status().isCreated());

		// Read from the column rather than from anything the create returned: the create path never
		// mentions library_derived at all, so what is under test is that the default holds.
		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE name = 'Toor Dal'", Boolean.class))
				.isFalse();

		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')].libraryDerived").value(false));

		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(0));
	}

	@Test
	@DisplayName("saving an edit clears the mark, and the count falls with it")
	void savingAnEditClearsTheMark() throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");

		mvc.perform(authed(get("/api/v1/ingredients/{id}", rice)))
				.andExpect(jsonPath("$.libraryDerived").value(true));
		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(jsonPath("$.count").value(1));

		mvc.perform(updateRequest(rice, "{\"name\":\"Sona Masuri Rice\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		// Read the row back rather than trust the 204. A save that succeeds and leaves the column
		// alone answers exactly the same way, which is the whole reason part 4 is asserted here.
		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isFalse();
		mvc.perform(authed(get("/api/v1/ingredients/{id}", rice)))
				.andExpect(jsonPath("$.libraryDerived").value(false));
		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(jsonPath("$.count").value(0));
	}

	@Test
	@DisplayName("saving with nothing changed does NOT clear the mark — a modification is required")
	void savingUnchangedLeavesTheMark() throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");

		/*
		 * The rule T-121 changed, and this is the assertion that proves it.
		 *
		 * T-119 shipped this the other way round on the argument that opening a row and looking at
		 * it IS the review. Rajeev overruled that on 2026-09-10, watching the screen: "When does the
		 * Imported lable get cleared, when the user goes to edit mode, makes atleast one
		 * modification and saves." So opening and saving is not a review, and the mark stands.
		 *
		 * Byte for byte what the row already holds.
		 */
		mvc.perform(updateRequest(rice, "{\"name\":\"Rice\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isTrue();
		// And the screen agrees with the column — the label and the count are both read from here.
		mvc.perform(authed(get("/api/v1/ingredients/{id}", rice)))
				.andExpect(jsonPath("$.libraryDerived").value(true));
		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(jsonPath("$.count").value(1));
	}

	@Test
	@DisplayName("re-typing the same value is not a modification, trimming and all")
	void retypingTheSameValueIsNotAModification() throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");

		/*
		 * The comparison is against the row in its STORED form, which is what makes "no change"
		 * mean what a person means by it. The service trims the name and the category and runs the
		 * aliases through normalizeAliases before writing, so a request carrying " Rice " and a
		 * duplicated alias produces a row identical to the one already there — and a comparison
		 * against the raw request would have called that a modification and cleared the mark.
		 */
		mvc.perform(updateRequest(rice, "{\"name\":\"  Rice  \",\"category\":\" Grains \","
						+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isTrue();
	}

	@Test
	@DisplayName("echoing the flag back unchanged is not a modification, and needs no permission")
	void echoingTheFlagBackIsNotAModification() throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");

		// The admin's own editing row seeds the checkbox from the row and sends the value it is
		// showing on every save, so an untouched box sends `false` against a stored `false`. That
		// has to read as "nothing moved" rather than as a write, or every opened-and-saved row
		// would clear its mark through the back door.
		mvc.perform(updateRequest(rice, "{\"name\":\"Rice\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"ekadashiProhibited\":false,"
						+ "\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isTrue();

		// Kitchen staff may echo it too, on a PROHIBITED row: the value matches, so the compliance
		// check has nothing to refuse. This is the 403 that a naive "the key is present" check would
		// have thrown at every Kitchen Manager renaming a prohibited ingredient.
		UUID jowar = createImportedIngredient("Jowar Flour", "Grains", "KG");
		admin.update("UPDATE ingredients SET is_ekadashi_prohibited = true WHERE id = ?", jowar);
		signIn("uid-staff-a");
		mvc.perform(updateRequest(jowar, "{\"name\":\"Jowar Flour\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"ekadashiProhibited\":true,"
						+ "\"aliases\":[]}"))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("changing one field clears the mark — any one of them")
	void changingAnyOneFieldClearsTheMark() throws Exception {
		// One row per editable field, so a failure names the field that stopped counting rather than
		// leaving somebody to bisect a single request that changed everything at once.
		assertClearedBy("{\"name\":\"Sona Masuri Rice\",\"category\":\"Grains\","
				+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}");
		assertClearedBy("{\"name\":\"Rice\",\"category\":\"Cereals\","
				+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}");
		assertClearedBy("{\"name\":\"Rice\",\"category\":\"Grains\","
				+ "\"unit\":\"GM\",\"supply\":false,\"aliases\":[]}");
		assertClearedBy("{\"name\":\"Rice\",\"category\":\"Grains\","
				+ "\"unit\":\"KG\",\"supply\":true,\"aliases\":[]}");
		assertClearedBy("{\"name\":\"Rice\",\"category\":\"Grains\","
				+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[\"Akki\"]}");
	}

	@Test
	@DisplayName("ticking Ekadashi and saving sets the flag AND clears the mark")
	void tickingEkadashiClearsTheMark() throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");

		// The behaviour change Rajeev asked for by name. The old one-click toggle cleared nothing
		// (see ekadashiFlagLeavesTheMarkAlone below, which still holds for that endpoint); ticking
		// the box inside the editing row is a deliberate edit, so it counts.
		mvc.perform(updateRequest(rice, "{\"name\":\"Rice\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"ekadashiProhibited\":true,"
						+ "\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT is_ekadashi_prohibited FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isTrue();
		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isFalse();
		// The compliance trail stays findable by its own action whichever route moved the flag.
		assertThat(auditCount("INGREDIENT_EKADASHI_FLAG_CHANGED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an omitted flag leaves the stored one alone — null is a statement, not a silence")
	void omittedFlagLeavesTheStoredValueAlone() throws Exception {
		UUID jowar = createIngredientAsAdmin("Jowar Flour", "Grains", "KG");
		admin.update("UPDATE ingredients SET is_ekadashi_prohibited = true WHERE id = ?", jowar);

		// The trap SupplyIngredientIT documents for `supply` — a primitive whose absent key becomes
		// false — asserted NOT to exist for this field, because the record declares a boxed Boolean
		// on purpose. Kitchen staff renaming a prohibited ingredient must not un-prohibit it.
		signIn("uid-staff-a");
		mvc.perform(updateRequest(jowar, "{\"name\":\"Jowar Atta\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT is_ekadashi_prohibited FROM ingredients WHERE id = ?", Boolean.class, jowar))
				.isTrue();
		assertThat(admin.queryForObject(
				"SELECT name FROM ingredients WHERE id = ?", String.class, jowar))
				.isEqualTo("Jowar Atta");
		assertThat(auditCount("INGREDIENT_EKADASHI_FLAG_CHANGED")).isZero();
	}

	@Test
	@DisplayName("kitchen staff cannot move the flag through an ordinary edit either")
	void staffCannotMoveTheFlagThroughAnEdit() throws Exception {
		UUID rice = createIngredientAsAdmin("Rice", "Grains", "KG");

		/*
		 * The hole T-121 could have opened, closed and asserted. PUT /{id} is behind MANAGE_RECIPES
		 * — kitchen staff reach it — so putting the flag on that body means the endpoint annotation
		 * no longer guards it. The check inside IngredientService.update is the only thing standing
		 * between them and a religious-compliance decision, and it is the same rule, the same code
		 * and the same reason as the one create() has always applied.
		 */
		signIn("uid-staff-a");
		mvc.perform(updateRequest(rice, "{\"name\":\"Rice\",\"category\":\"Grains\","
						+ "\"unit\":\"KG\",\"supply\":false,\"ekadashiProhibited\":true,"
						+ "\"aliases\":[]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400021"));

		// Nothing was written — not the flag, and not the name beside it, because the whole
		// transaction is refused before the UPDATE.
		assertThat(admin.queryForObject(
				"SELECT is_ekadashi_prohibited FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isFalse();
	}

	@Test
	@DisplayName("the Ekadashi toggle is deliberately not a second clearing path")
	void ekadashiFlagLeavesTheMarkAlone() throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");

		mvc.perform(ekadashiRequest(rice, true)).andExpect(status().isNoContent());

		// It writes one religious-compliance flag from a one-click toggle on the row, without ever
		// opening the row or showing anybody the category and unit the import guessed — and it
		// returns early when the flag is already what was asked for, so a click that cleared the
		// mark and a click that did not would look identical to the person pressing it.
		//
		// Rajeev's wording is "the mark clears when somebody edits and saves the ingredient", and
		// the edit form is the only thing that does that. If he rules the other way, this is the
		// test that says so and it is the one to change.
		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.isTrue();
	}

	@Test
	@DisplayName("the count is the temple's own — another temple's imports are not in it")
	void countIsScopedToTheTenant() throws Exception {
		createImportedIngredient("Rice", "Grains", "KG");
		admin.update("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, library_derived)
				VALUES (?, 'Jaggery', 'Sweeteners', 'KG', true)
				""", templeB);

		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1));
	}

	// ---------------------------------------------------------------------

	/**
	 * A fresh imported Rice, edited with the given body, asserted to come back unmarked.
	 *
	 * <p>Each call makes its own row and deletes it again, so the five field cases in
	 * {@link #changingAnyOneFieldClearsTheMark} cannot collide on the catalogue's unique name.
	 */
	private void assertClearedBy(String json) throws Exception {
		UUID rice = createImportedIngredient("Rice", "Grains", "KG");
		mvc.perform(updateRequest(rice, json)).andExpect(status().isNoContent());
		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, rice))
				.as("save of %s should have counted as a modification", json)
				.isFalse();
		admin.update("DELETE FROM ingredients WHERE id = ?", rice);
	}

	/** A row exactly as {@code RecipeImportService} leaves one: marked, with a guessed category. */
	private UUID createImportedIngredient(String name, String category, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, library_derived)
				VALUES (?, ?, ?, ?, true) RETURNING id
				""", UUID.class, templeA, name, category, unit);
	}

	private MockHttpServletRequestBuilder updateRequest(UUID id, String json) {
		return authed(put("/api/v1/ingredients/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private UUID createIngredientAsAdmin(String name, String category, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, templeA, name, category, unit);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/ingredients")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder ekadashiRequest(UUID id, boolean prohibited) {
		return authed(patch("/api/v1/ingredients/{id}/ekadashi-flag", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"ekadashiProhibited\":" + prohibited + "}");
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
