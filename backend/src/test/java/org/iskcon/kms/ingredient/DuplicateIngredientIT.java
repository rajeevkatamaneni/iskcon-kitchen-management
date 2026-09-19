package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * No duplicate ingredients (R-DUP-2, T-251), through the full stack with RLS really in play.
 *
 * <p>Duplicate ingredients split stock, prices and shopping-list lines, so a create or a rename
 * whose name the temple already has — or very nearly has — is stopped with KMS-400156 and the
 * ingredient it looks like, until the person confirms it is a different one. That confirmation
 * saves and is audited. The literal same name keeps its older refusal, KMS-400034, and nothing
 * overrides that one. Another temple's ingredients are never compared against, because the
 * database never shows them.
 *
 * <p>The matching rule itself (what "close" means) is {@link IngredientNameMatcher}'s and is tested
 * there, 147 cases. What is tested here is that the service asks it, against the right rows, and
 * does the right thing with the answer.
 */
@AutoConfigureMockMvc
class DuplicateIngredientIT extends AbstractIntegrationTest {

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
		stubVerifier.accept("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredient_aliases");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---------------------------------------------------------------- the two ACs

	@Test
	@DisplayName("AC: creating 'Tomatos' when 'Tomato, ripe' exists is stopped, naming Tomato, ripe")
	void tomatosIsStoppedByTomatoRipe() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato, ripe");

		mvc.perform(create("Tomatos", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientId')].message")
						.value(tomato.toString()))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientName')].message")
						.value("Tomato, ripe"));

		assertThat(countNamed("Tomatos")).isZero();
		assertThat(auditCount("INGREDIENT_ADDED")).isZero();
	}

	@Test
	@DisplayName("AC: creating 'Curd sour' when 'Curd' exists is stopped, naming Curd")
	void curdSourIsStoppedByCurd() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd");

		mvc.perform(create("Curd sour", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientId')].message")
						.value(curd.toString()))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientName')].message")
						.value("Curd"));

		assertThat(countNamed("Curd sour")).isZero();
	}

	@Test
	@DisplayName("confirmDifferent: false is the same as leaving it out — still stopped")
	void confirmDifferentFalseIsStillChecked() throws Exception {
		insertIngredient(templeA, "Curd");

		mvc.perform(create("Curd sour", false))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"));
	}

	// ---------------------------------------------------------------- the way past, audited

	@Test
	@DisplayName("confirmDifferent saves it, and the audit records the override and what it looked like")
	void confirmDifferentSavesAndAudits() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd");

		mvc.perform(create("Curd sour", true))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").exists());

		assertThat(countNamed("Curd sour")).isEqualTo(1);
		assertThat(auditCount("INGREDIENT_ADDED")).isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT reason FROM audit_events WHERE action = 'INGREDIENT_ADDED'", String.class))
				.isEqualTo("Added although it looks like “Curd”: confirmed as a different ingredient.");
		assertThat(admin.queryForObject(
				"SELECT after_state->'confirmedDifferentFrom'->>'id' FROM audit_events WHERE action = 'INGREDIENT_ADDED'",
				String.class))
				.isEqualTo(curd.toString());
		assertThat(admin.queryForObject(
				"SELECT after_state->'confirmedDifferentFrom'->>'name' FROM audit_events WHERE action = 'INGREDIENT_ADDED'",
				String.class))
				.isEqualTo("Curd");
	}

	@Test
	@DisplayName("a name nothing resembles saves with no override on the audit, confirm flag or not")
	void anUnrelatedNameIsNotAnOverride() throws Exception {
		insertIngredient(templeA, "Curd");

		mvc.perform(create("Jaggery", true)).andExpect(status().isCreated());

		assertThat(admin.queryForObject(
				"SELECT reason FROM audit_events WHERE action = 'INGREDIENT_ADDED'", String.class))
				.isNull();
		assertThat(admin.queryForObject(
				"SELECT jsonb_exists(after_state, 'confirmedDifferentFrom') FROM audit_events WHERE action = 'INGREDIENT_ADDED'",
				Boolean.class))
				.isFalse();
	}

	// ---------------------------------------------------------------- the older refusal stands

	@Test
	@DisplayName("the literal same name is still KMS-400034, and confirming does not get past it")
	void theSameNameKeepsItsOwnRefusal() throws Exception {
		insertIngredient(templeA, "Curd");

		mvc.perform(create("curd", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400034"));
		mvc.perform(create("  CURD ", true))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400034"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE lower(name) = 'curd'", Integer.class))
				.isEqualTo(1);
	}

	// ---------------------------------------------------------------- tenant isolation

	@Test
	@DisplayName("RLS: another temple's 'Curd' never matches — only this temple's catalogue is compared")
	void anotherTemplesCurdNeverMatches() throws Exception {
		insertIngredient(templeB, "Curd");
		insertAlias(templeB, insertIngredient(templeB, "Tomato, ripe"), "Tamatar", "tamatar");

		mvc.perform(create("Curd sour", null)).andExpect(status().isCreated());
		mvc.perform(create("Tomatos", null)).andExpect(status().isCreated());
		mvc.perform(create("Tamatar", null)).andExpect(status().isCreated());

		// Saved with nothing overridden: the audit carries no reason, because nothing was matched.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INGREDIENT_ADDED' AND reason IS NOT NULL",
				Integer.class))
				.isZero();
		// And the positive half, so this is not green for the wrong reason: the same name against
		// this temple's own Curd IS stopped.
		insertIngredient(templeA, "Curd");
		mvc.perform(create("Curds", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"));
	}

	// ---------------------------------------------------------------- renames

	@Test
	@DisplayName("a rename to a lookalike is stopped; confirming saves it and audits the override")
	void renameToALookalike() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd");
		UUID paneer = insertIngredient(templeA, "Paneer");

		mvc.perform(update(paneer, "Curd sour", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientId')].message")
						.value(curd.toString()));
		assertThat(nameOf(paneer)).isEqualTo("Paneer");

		mvc.perform(update(paneer, "Curd sour", true)).andExpect(status().isNoContent());
		assertThat(nameOf(paneer)).isEqualTo("Curd sour");
		assertThat(admin.queryForObject(
				"SELECT reason FROM audit_events WHERE action = 'INGREDIENT_UPDATED'", String.class))
				.isEqualTo("Renamed although it looks like “Curd”: confirmed as a different ingredient.");
	}

	@Test
	@DisplayName("saving a row without renaming it is never stopped, even beside an existing lookalike")
	void anUnchangedNameIsNotChecked() throws Exception {
		// Both already in the catalogue, as imports left many temples before this guard existed.
		insertIngredient(templeA, "Curd");
		UUID sour = insertIngredient(templeA, "Curd, sour");

		mvc.perform(update(sour, "Curd, sour", null, "[\"Huli mosaru\"]"))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("a row never matches itself: a case-only rename with nothing else like it saves")
	void aRowNeverMatchesItself() throws Exception {
		UUID curd = insertIngredient(templeA, "curd");

		mvc.perform(update(curd, "Curd", null)).andExpect(status().isNoContent());
		assertThat(nameOf(curd)).isEqualTo("Curd");
	}

	// ---------------------------------------------------------------- the alias table

	@Test
	@DisplayName("aliases typed on the ingredient are kept in ingredient_aliases, normalised, on create and update")
	void aliasRowsFollowTheTypedAliases() throws Exception {
		String body = mvc.perform(createWithAliases("Curd", "[\"Dahi\", \"DAHI.\", \"Mosaru\"]"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID curd = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));

		// "Dahi" and "DAHI." normalise alike, so they are one row, the first as typed.
		assertThat(aliasRows(curd)).containsExactly("dahi|Dahi", "mosaru|Mosaru");
		// The array on the row is untouched: every alias as typed.
		assertThat(admin.queryForObject(
				"SELECT array_length(aliases, 1) FROM ingredients WHERE id = ?", Integer.class, curd))
				.isEqualTo(3);

		mvc.perform(update(curd, "Curd", null, "[\"Thayir\", \"Mosaru\"]"))
				.andExpect(status().isNoContent());
		assertThat(aliasRows(curd)).containsExactly("mosaru|Mosaru", "thayir|Thayir");

		// Written under the acting temple's id, from the session, never from the request.
		assertThat(admin.queryForObject(
				"SELECT DISTINCT tenant_id FROM ingredient_aliases WHERE ingredient_id = ?", UUID.class, curd))
				.isEqualTo(templeA);
	}

	@Test
	@DisplayName("a name matching another ingredient's alias is stopped, including an alias held only in the table")
	void aNameMatchingAnAliasIsStopped() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd");
		// As the merge tool (R-DUP-3) will leave a merged-away name: in the table, not in the array.
		insertAlias(templeA, curd, "Thayir", "thayir");

		mvc.perform(create("Thayir", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientName')].message")
						.value("Curd"));
	}

	@Test
	@DisplayName("a newly typed alias another ingredient already answers to is refused, and nothing is saved")
	void aClashingNewAliasIsRefused() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd");
		insertAlias(templeA, curd, "Mosaru", "mosaru");
		UUID paneer = insertIngredient(templeA, "Paneer");

		mvc.perform(update(paneer, "Paneer", null, "[\"Mosaru\"]"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400034"));

		assertThat(admin.queryForObject(
				"SELECT array_length(aliases, 1) FROM ingredients WHERE id = ?", Integer.class, paneer))
				.isNull();
		assertThat(aliasRows(curd)).containsExactly("mosaru|Mosaru");
	}

	@Test
	@DisplayName("an alias the row already had, left to another ingredient by the backfill, does not block its saves")
	void aPreExistingAliasClashDoesNotBlockSaves() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd");
		insertAlias(templeA, curd, "Mosaru", "mosaru");
		// The shape V144's backfill can leave: the array says Mosaru, the table gave it to Curd.
		UUID paneer = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, aliases)
				VALUES (?, 'Paneer', 'Dairy', 'KG', ARRAY['Mosaru']) RETURNING id
				""", UUID.class, templeA);

		mvc.perform(update(paneer, "Paneer", null, "[\"Mosaru\"]"))
				.andExpect(status().isNoContent());
		assertThat(aliasRows(curd)).containsExactly("mosaru|Mosaru");
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder create(String name, Boolean confirmDifferent) {
		return authed(post("/api/v1/ingredients")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"category\":\"Dairy\",\"unit\":\"KG\","
						+ "\"supply\":false,\"aliases\":[]"
						+ (confirmDifferent == null ? "" : ",\"confirmDifferent\":" + confirmDifferent)
						+ "}");
	}

	private MockHttpServletRequestBuilder createWithAliases(String name, String aliasesJson) {
		return authed(post("/api/v1/ingredients")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"category\":\"Dairy\",\"unit\":\"KG\","
						+ "\"supply\":false,\"aliases\":" + aliasesJson + "}");
	}

	private MockHttpServletRequestBuilder update(UUID id, String name, Boolean confirmDifferent) {
		return update(id, name, confirmDifferent, "[]");
	}

	private MockHttpServletRequestBuilder update(
			UUID id, String name, Boolean confirmDifferent, String aliasesJson) {
		return authed(put("/api/v1/ingredients/{id}", id)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"category\":\"Dairy\",\"unit\":\"KG\","
						+ "\"supply\":false,\"aliases\":" + aliasesJson
						+ (confirmDifferent == null ? "" : ",\"confirmDifferent\":" + confirmDifferent)
						+ "}");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private UUID insertIngredient(UUID tenant, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Dairy', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void insertAlias(UUID tenant, UUID ingredient, String alias, String normalised) {
		admin.update("""
				INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
				VALUES (?, ?, ?, ?)
				""", tenant, ingredient, alias, normalised);
	}

	private List<String> aliasRows(UUID ingredient) {
		return admin.queryForList("""
				SELECT normalised_alias || '|' || alias FROM ingredient_aliases
				WHERE ingredient_id = ? ORDER BY normalised_alias
				""", String.class, ingredient);
	}

	private int countNamed(String name) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE name = ?", Integer.class, name);
		return c == null ? 0 : c;
	}

	private String nameOf(UUID id) {
		return admin.queryForObject("SELECT name FROM ingredients WHERE id = ?", String.class, id);
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
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
