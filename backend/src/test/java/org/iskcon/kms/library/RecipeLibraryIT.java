package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.BeforeAll;
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
 * The shared recipe library, end to end (E2-S9, E2-S10, E2-S12, E2-S15).
 *
 * <p>Loaded from the <em>real</em> vendored books rather than a fixture. A fixture would prove the
 * loader can read a file somebody wrote to make the test pass; the risk being carried here is the
 * 5,376 recipes a person wrote to be printed, and those are the ones that have to go in.
 */
@AutoConfigureMockMvc
@Import(RecipeLibraryIT.StubVerifierConfiguration.class)
class RecipeLibraryIT extends AbstractIntegrationTest {

	/** 32 books, 21 categories of 8 dishes each. */
	private static final int EXPECTED_RECIPES = 5376;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private LibraryLoader loader;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeAll
	static void quiet() {
		// Nothing to do; kept as the place to say that this class loads the whole library once per
		// test method on purpose — the load is the thing under test, and it takes about a second.
	}

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN");
		insertUser(templeB, "uid-admin-b", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-cook-a", "KITCHEN_STAFF");
		insertOperator("uid-operator");
		seedCategories(templeA);
		seedCategories(templeB);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM master_recipes");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ------------------------------------------------------------------ E2-S9

	@Test
	@DisplayName("every book loads, and loading twice leaves the same 5,376 rows")
	void loadsAndReloads() {
		LibraryLoader.Result first = loader.load();

		assertThat(first.books()).isEqualTo(32);
		assertThat(first.recipes()).isEqualTo(EXPECTED_RECIPES);
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);

		// Every book holds 168 — 21 categories of 8 — and a book that lost recipes on the way in
		// would show up here rather than as a gap somebody notices next year.
		List<Integer> perBook = admin.queryForList(
				"SELECT count(*) FROM master_recipes GROUP BY state_slug", Integer.class);
		assertThat(perBook).hasSize(32).allMatch(n -> n == 168);

		loader.load();
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);
	}

	@Test
	@DisplayName("the disambiguation ladder gives 5,376 distinct names, and does not depend on file order")
	void ladder() {
		LibraryLoader.Result result = loader.load();

		Integer distinct = admin.queryForObject(
				"SELECT count(DISTINCT lower(display_name)) FROM master_recipes", Integer.class);
		assertThat(distinct).isEqualTo(EXPECTED_RECIPES);

		assertThat(result.bare()).isEqualTo(3504);
		assertThat(result.withState()).isEqualTo(1870);
		assertThat(result.withStateAndCategory()).isEqualTo(2);

		// The hole the two-pass count exists to close: the *first* Sabudana Khichdi has never been
		// seen before, so a streaming "suffix it if I have seen this" would let one of the seventeen
		// through bare — and which one depends on the order the files were read in.
		List<String> sabudana = admin.queryForList("""
				SELECT display_name FROM master_recipes
				WHERE lower(name) = 'sabudana khichdi' ORDER BY display_name
				""", String.class);
		assertThat(sabudana).hasSize(17).allMatch(n -> n.contains(" ("));
	}

	@Test
	@DisplayName("two dishes of one name in one state are separated by their category, not left to collide")
	void thirdRung() {
		loader.load();

		// Alugadde Palya is in the Karnataka book twice: under Ekadashi with rock salt and no
		// mustard, and under Sabji's Dry with a full tempering. A cook given the wrong one on a fast
		// day has broken the fast.
		List<String> palya = admin.queryForList("""
				SELECT display_name FROM master_recipes
				WHERE lower(name) = 'alugadde palya' ORDER BY display_name
				""", String.class);
		assertThat(palya).containsExactlyInAnyOrder(
				"Alugadde Palya (Karnataka, Ekadashi)",
				"Alugadde Palya (Karnataka, Sabji's, Dry)");
	}

	@Test
	@DisplayName("every yield and every ingredient quantity resolved — nothing was skipped")
	void everythingParsed() {
		loader.load();

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM master_recipes WHERE yield_qty IS NULL OR yield_qty <= 0",
				Integer.class)).isZero();

		// The three yield units the books actually use, in the proportions they use them.
		assertThat(unitCount("LITRES")).isEqualTo(2918);
		assertThat(unitCount("KG")).isEqualTo(1619);
		assertThat(unitCount("PIECES")).isEqualTo(839);

		// 5,032 books state a portion; 5,031 are kept. The one dropped is Delhi's Papdi, which is
		// made by the kilo and served by the piece — see BookParserTest.mismatchedFamily.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM master_recipes WHERE per_head_qty IS NOT NULL", Integer.class))
				.isEqualTo(5031);
	}

	// ------------------------------------------------------------------ isolation

	@Test
	@DisplayName("a cook may read the library; nobody but an operator may change it, and the database says so")
	void isolation() {
		loader.load();
		UUID any = admin.queryForObject("SELECT id FROM master_recipes LIMIT 1", UUID.class);

		signIn("uid-cook-a");
		assertThatCookCanRead(any);

		// The endpoint refuses first...
		assertThat(statusOfWriteAttempt()).isEqualTo(403);

		// ...and underneath it, so does the policy. Run as the application role carrying a temple
		// admin's identity — the strongest identity that is not an operator — with no application
		// code in the way at all.
		//
		// A DELETE refused by RLS removes nothing rather than raising: the USING clause filters the
		// rows away, so there is nothing to delete and nothing to complain about. That is the right
		// shape for a read-side control and the reason the assertion is a count, not a throw. A
		// write with a WITH CHECK — an insert — does raise, and that is asserted below.
		asUser("uid-admin-a", jdbc -> jdbc.update("DELETE FROM master_recipes WHERE id = ?", any));
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);

		// A refused INSERT does raise — SQLSTATE 42501, which Spring surfaces as a grammar
		// exception rather than an access one. The assertion is that it was refused and that
		// nothing landed, not on the wording of a driver's message.
		assertThatThrownBy(() -> asUser("uid-admin-a", jdbc -> jdbc.update("""
				INSERT INTO master_recipes (state_slug, state, book_language, recipe_slug, name,
					display_name, category_key, category_name, badge, yield_text, yield_qty,
					yield_unit, why, ingredients, method, source_ref)
				VALUES ('x', 'X', 'English', 'x', 'X', 'X', 'x', 'X', 'Everyday', '1 L', 1, 'LITRES',
					'because', '[]'::jsonb, '[]'::jsonb, 'hand')
				""")))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);
	}

	@Test
	@DisplayName("deleting a temple leaves the library standing")
	void tenantDeletionLeavesLibrary() {
		loader.load();
		admin.queryForObject("SELECT delete_tenant_cascade(?)::text", String.class, templeB);
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);
	}

	// ------------------------------------------------------------------ E2-S10

	@Test
	@DisplayName("one box finds the temple's own and the library's, and marks what is already taken")
	void search() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "majjige")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.origin=='LIBRARY')]").exists());

		// Typing an ingredient finds the dishes that contain it.
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "asafoetida")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].origin").value("LIBRARY"));

		// A tag is searchable; prose is not. "Jain-safe" is a fact about a dish, and a method step
		// is a paragraph nearly every recipe shares.
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "jain")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0]").exists());
	}

	@Test
	@DisplayName("an empty box shows the temple's own and no library rows at all")
	void emptySearch() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		mvc.perform(authed(get("/api/v1/recipes/search")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.origin=='LIBRARY')]").doesNotExist());
	}

	// ------------------------------------------------------------------ E2-S12

	@Test
	@DisplayName("adding a library recipe creates a full copy, its ingredients and its category")
	void importCreatesCopy() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID majjige = libraryId("Majjige");

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", majjige)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Majjige"));

		Map<String, Object> copy = admin.queryForMap("""
				SELECT r.name, r.base_yield_unit, r.per_head_qty, r.master_recipe_id, r.tenant_id,
				       c.name AS category
				FROM recipes r JOIN recipe_categories c ON c.id = r.category_id
				WHERE r.name = 'Majjige'
				""");
		assertThat(copy.get("tenant_id")).isEqualTo(templeA);
		assertThat(copy.get("base_yield_unit")).isEqualTo("LITRES");
		assertThat(copy.get("master_recipe_id")).isEqualTo(majjige);
		assertThat(copy.get("category")).isEqualTo("Beverages");
		assertThat(copy.get("per_head_qty")).isNotNull();

		// The lines came across in the book's order, against ingredients created for the purpose.
		Integer lines = admin.queryForObject(
				"SELECT count(*) FROM recipe_ingredients WHERE tenant_id = ?", Integer.class, templeA);
		assertThat(lines).isEqualTo(8);

		// Every created ingredient got a unit and a category — the column is NOT NULL, and the books
		// carry no category at all.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ? AND library_derived", Integer.class, templeA))
				.isEqualTo(8);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ? AND (category IS NULL OR canonical_unit IS NULL)",
				Integer.class, templeA)).isZero();
	}

	@Test
	@DisplayName("a second import of the same recipe is refused, and so is one whose name is taken")
	void refusesDuplicates() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID majjige = libraryId("Majjige");

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", majjige))).andExpect(status().isCreated());

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", majjige)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4968"));

		// And the name rule, which is what a temple that typed the dish in by hand last year meets.
		admin.update("DELETE FROM recipe_ingredients");
		admin.update("UPDATE recipes SET master_recipe_id = NULL WHERE tenant_id = ?", templeA);
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", majjige)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4905"));
	}

	@Test
	@DisplayName("a prohibited ingredient refuses the import and leaves nothing behind")
	void refusesProhibited() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID majjige = libraryId("Majjige");

		// Curd is in Majjige. Flagging it is not realistic; it is the cheapest way to prove the rule
		// bites, and that a refusal is not a half-finished import.
		admin.update("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_sattvic_prohibited)
				VALUES (?, 'Curd, fresh', 'Dairy', 'L', true)
				""", templeA);

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", majjige)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4970"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM recipes WHERE tenant_id = ?", Integer.class, templeA)).isZero();
		// The one ingredient that existed before is still the only one.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, templeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("a recipe whose name merely contains a prohibited word still imports")
	void substringIsNotTheRule() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		// "Onion-free chaat masala" and "Garlic-free panch phoron" are the only two ingredient names
		// in the whole library carrying a prohibited word, and both are sattvic. A substring check
		// would refuse precisely the two recipes most careful about the rule.
		UUID bhuja = admin.queryForObject("""
				SELECT id FROM master_recipes
				WHERE state_slug = 'jharkhand' AND lower(name) = 'bhuja' LIMIT 1
				""", UUID.class);
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", bhuja)))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a copy is the temple's own: editing it changes nothing in the library or elsewhere")
	void copyIsIndependent() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID majjige = libraryId("Majjige");
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", majjige))).andExpect(status().isCreated());

		admin.update("UPDATE recipes SET per_head_qty = 0.5 WHERE tenant_id = ?", templeA);

		assertThat(admin.queryForObject(
				"SELECT per_head_qty FROM master_recipes WHERE id = ?", java.math.BigDecimal.class, majjige))
				.isEqualByComparingTo("0.2");

		// And temple B sees none of it.
		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "majjige")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.origin=='MINE')]").doesNotExist());
	}

	// ------------------------------------------------------------------ helpers

	private void assertThatCookCanRead(UUID id) {
		try {
			mvc.perform(authed(get("/api/v1/library/recipes/{id}", id))).andExpect(status().isOk());
		} catch (Exception e) {
			throw new AssertionError("a cook could not read the library", e);
		}
	}

	private int statusOfWriteAttempt() {
		try {
			return mvc.perform(authed(post("/api/v1/library/recipes/load")))
					.andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Runs a statement as the unprivileged application role, carrying one person's verified
	 * identity. The point of going round the application: the policy has to refuse this on its own,
	 * with no Java in the way.
	 */
	private void asUser(String uid, java.util.function.Consumer<JdbcTemplate> work) {
		org.springframework.jdbc.datasource.DriverManagerDataSource plain =
				new org.springframework.jdbc.datasource.DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		org.iskcon.kms.tenancy.TenantContext.setAuthLookupUid(uid);
		try {
			work.accept(new JdbcTemplate(new org.iskcon.kms.tenancy.TenantAwareDataSource(plain)));
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	private UUID libraryId(String name) {
		return admin.queryForObject(
				"SELECT id FROM master_recipes WHERE display_name = ? LIMIT 1", UUID.class, name);
	}

	private int count(String table) {
		Integer n = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return n == null ? 0 : n;
	}

	private int unitCount(String unit) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM master_recipes WHERE yield_unit = ?", Integer.class, unit);
		return n == null ? 0 : n;
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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

	private void insertUser(UUID tenantId, String uid, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", role);
	}

	private void insertOperator(String uid) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, ?, 'Platform Operator', ?, '+919876500099', 'SUPER_ADMIN', 'ACTIVE')
				""", uid, uid + "@example.com");
	}

	/** The nine a temple is provisioned with, so an import can find Beverages without creating it. */
	private void seedCategories(UUID tenantId) {
		for (String name : new String[] {
				"Beverages", "Breakfast", "Rice", "Dal", "Sabji", "Roti", "Sweets", "Snacks"}) {
			admin.update("""
					INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
					VALUES (?, ?, false)
					""", tenantId, name);
		}
		admin.update("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Ekadashi', true)
				""", tenantId);
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
