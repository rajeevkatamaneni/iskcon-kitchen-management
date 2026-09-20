package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
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
 * An ingredient the book says the temple never buys, carried from the book to the temple's
 * catalogue — and stopping short of the temple's own rows (T-403).
 *
 * <p><strong>What was wrong.</strong> T-402 made {@code ingredients.is_not_bought} real: a marked
 * ingredient never reaches a shopping list. Nothing set it from the book. Rajeev's curated recipes
 * (2026-09-19) mark fifteen lines, every one of them water, and the library path dropped the key
 * before anything could read it — so loading his catalogue created water as an ordinary bought
 * ingredient and put it on every list, which is precisely what T-402 exists to prevent.
 *
 * <p><strong>The decision this class pins down.</strong> The mark travels onto an ingredient the
 * import <em>creates</em>, and never onto one the temple already holds. Copying a library recipe is
 * {@code MANAGE_RECIPES} — all three kitchen roles — while marking an ingredient is
 * {@code MANAGE_BUYING_POLICY}, which T-402 gave to the Temple Admin alone behind an audited
 * endpoint of its own. An import that flipped the flag on a row the temple owns would let a Kitchen
 * Manager set a flag they are not allowed to set, silently, and stop the temple buying something
 * with nothing on the screen to say so. Creating a new row marked is a different act: the temple
 * held no opinion about a row that did not exist, the authority is the operator who curated the book
 * ({@code MANAGE_RECIPE_LIBRARY}), and a wrong mark shows up as something missing from an order and
 * clears in one click. Where the book and the temple disagree, the names go into the import's audit
 * entry rather than nowhere — {@link #anExistingIngredientIsLeftAloneAndTheDisagreementIsRecorded}.
 *
 * <p><strong>Absence is never asserted on its own.</strong> The shopping-list test drives the list
 * with the mark in place, then clears the mark through the real Temple Admin endpoint and watches
 * the line come back, so "no water" cannot pass because the water was never demanded.
 */
@AutoConfigureMockMvc
class RecipeImportNotBoughtIT extends AbstractIntegrationTest {

	/** T-401's one-recipe fixture book, whose water line carries prep "Hot" and not_bought true. */
	private static final String FIXTURE_BOOK = "classpath:prep-book/*.json";

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private LibraryLoader loader;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID staffId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin-a', 'Admin A', 'admin-a@example.com', '+919876500081',
					'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, temple);
		// A Kitchen Manager as well, because the whole argument above turns on who is allowed to do
		// what: the import is theirs, the buying policy is not.
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-manager-a', 'Manager A', 'manager-a@example.com', '+919876500082',
					'KITCHEN_MANAGER', 'ACTIVE')
				""", temple);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-operator', 'Platform Operator', 'operator@example.com',
					'+919876500099', 'SUPER_ADMIN', 'ACTIVE')
				""");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM shopping_list_lines");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredient_aliases");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM master_recipes");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ------------------------------------------------------------------ the loader and the API

	@Test
	@DisplayName("a load reads not_bought out of a book, stores it on every line, and GET returns it")
	void loadCarriesTheMarkThroughToTheApi() throws Exception {
		loader.load(FIXTURE_BOOK);

		UUID id = admin.queryForObject(
				"SELECT id FROM master_recipes WHERE recipe_slug = 'akki-rotti-curated'", UUID.class);

		// The row first. Cast to text rather than read with ->>, so that a line with no key at all
		// (NULL here) cannot be confused with a line that says false. Four lines, one of them water.
		assertThat(storedMarks(id)).containsExactly("false", "true", "false", "false");

		String body = mvc.perform(authed(get("/api/v1/library/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[1].name").value("Water"))
				.andExpect(jsonPath("$.ingredients[1].prep").value("Hot"))
				.andReturn().getResponse().getContentAsString();

		// An indefinite path returns one element per line that HAS the key, so four booleans is the
		// whole assertion: the server sends notBought on every line, not only where it is true. A
		// response that left it off the bought lines would come back with one.
		assertThat(JsonPath.<List<Boolean>>read(body, "$.ingredients[*].notBought"))
				.containsExactly(false, true, false, false);
	}

	@Test
	@DisplayName("the operator's editor round-trips it: saving a curated recipe back keeps the mark")
	void operatorsSaveKeepsTheMark() throws Exception {
		loader.load(FIXTURE_BOOK);
		UUID id = admin.queryForObject(
				"SELECT id FROM master_recipes WHERE recipe_slug = 'akki-rotti-curated'", UUID.class);

		// The same trap T-401 found for prep, and a more expensive one: a save that dropped this key
		// would put water back on the shopping list of every temple that copied the recipe after.
		signIn("uid-operator");
		mvc.perform(authed(put("/api/v1/library/recipes/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "name": "Akki Rotti Curated",
						  "state": "Prep Fixture", "stateSlug": "prep-fixture",
						  "recipeSlug": "akki-rotti-curated",
						  "categoryKey": "breakfast-items", "categoryName": "Breakfast Items",
						  "badge": "Everyday",
						  "yieldText": "200 rottis", "yieldQty": 200, "yieldUnit": "PIECES",
						  "why": "Because the mark has to survive a save.",
						  "ingredients": [
						    {"name": "Rice flour", "qty": "12 Kg"},
						    {"name": "Water", "qty": "14 L", "prep": "Hot", "notBought": true},
						    {"name": "Coconut", "qty": "16 Pieces", "prep": "Grated"},
						    {"name": "Green chilli", "qty": "250 gm", "prep": "Chopped"}
						  ],
						  "method": ["Mix.", "Cook."]
						}
						"""))
				.andExpect(status().isNoContent());

		assertThat(storedMarks(id)).containsExactly("false", "true", "false", "false");
		signIn("uid-admin-a");
		String body = mvc.perform(authed(get("/api/v1/library/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Boolean>>read(body, "$.ingredients[*].notBought"))
				.containsExactly(false, true, false, false);
	}

	@Test
	@DisplayName("a row written before T-403 has no key at all and reads as bought, not as unknown")
	void anOlderRowReadsAsBought() throws Exception {
		// Every one of the 5,376 vendored rows was this shape, and any row still in a temple's
		// database from before the curated catalogue replaced them on 2026-09-19 still is. Written
		// with no not_bought key, which is what `legacyLine` leaves out, so the reader is exercised
		// against a real old row rather than against a false somebody wrote for the test.
		UUID master = insertLibraryRecipe("Plain Rice", legacyLine("Rice flour", "12 Kg", "12", "KG"));

		assertThat(storedMarks(master)).containsExactly((String) null);
		String body = mvc.perform(authed(get("/api/v1/library/recipes/{id}", master)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Boolean>>read(body, "$.ingredients[*].notBought")).containsExactly(false);

		importIt(master);
		assertThat(isNotBought("Rice flour")).isFalse();
	}

	// ------------------------------------------------------------------ the import

	@Test
	@DisplayName("an ingredient the import creates from a marked line is created marked, and the rest are not")
	void anImportCreatesTheIngredientMarked() throws Exception {
		UUID master = curatedAkkiRotti();

		String recipeId = importIt(master);

		assertThat(ingredientNames()).containsExactly("Rice flour", "Water");
		assertThat(isNotBought("Water")).isTrue();
		assertThat(isNotBought("Rice flour"))
				.as("the mark is per line, not per import — an unmarked line stays bought")
				.isFalse();
		// And T-401's field still arrives on the same line, because the two travel together.
		assertThat(noteOn(recipeId, "Water")).isEqualTo("Hot");
		assertThat(noteOn(recipeId, "Rice flour")).isNull();
	}

	@Test
	@DisplayName("creating one marked is audited exactly as the ingredient form audits the same thing")
	void creatingOneMarkedIsAuditedLikeTheForm() throws Exception {
		UUID master = curatedAkkiRotti();

		importIt(master);

		UUID water = ingredientId("Water");
		// The same action, the same two maps. IngredientService writes this from its own create for
		// the reason its comment gives — "who said we never buy this" should have one action to grep
		// for whichever route set it — and the import was the one route that left no findable trace.
		Map<String, Object> entry = admin.queryForMap("""
				SELECT before_state::text AS before_state, after_state::text AS after_state
				FROM audit_events WHERE action = 'INGREDIENT_NOT_BOUGHT_CHANGED' AND entity_id = ?
				""", water);
		assertThat(JSON.readTree((String) entry.get("before_state")))
				.isEqualTo(JSON.readTree("{\"name\":\"Water\",\"notBought\":false}"));
		assertThat(JSON.readTree((String) entry.get("after_state")))
				.isEqualTo(JSON.readTree("{\"name\":\"Water\",\"notBought\":true}"));

		// One per marked ingredient created, and none for the bought one beside it.
		assertThat(auditCount("INGREDIENT_NOT_BOUGHT_CHANGED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an ingredient the temple already has is left exactly as the temple has it, and the disagreement is recorded")
	void anExistingIngredientIsLeftAloneAndTheDisagreementIsRecorded() throws Exception {
		// A temple that has been buying bottled water for a year. The book says it never buys water.
		UUID water = insertIngredient("Water", "L");
		UUID master = curatedAkkiRotti();

		String recipeId = importIt(master);

		assertThat(admin.queryForObject(
				"SELECT is_not_bought FROM ingredients WHERE id = ?", Boolean.class, water))
				.as("the import does not make the temple's buying policy")
				.isFalse();
		assertThat(auditCount("INGREDIENT_NOT_BOUGHT_CHANGED"))
				.as("nothing moved, so nothing is recorded as having moved")
				.isZero();

		// Not swallowed: the one thing about the copy that did not come out the way the book wrote it
		// is in the import's own entry, by the temple's name for the ingredient.
		assertThat(importedAudit(recipeId).at("/notBoughtNotApplied").toString())
				.isEqualTo("[\"Water\"]");
	}

	@Test
	@DisplayName("nothing is recorded when the temple already agrees with the book")
	void agreementIsNotADisagreement() throws Exception {
		UUID water = insertIngredient("Water", "L");
		setNotBought(water, true);
		UUID master = curatedAkkiRotti();

		String recipeId = importIt(master);

		assertThat(importedAudit(recipeId).has("notBoughtNotApplied"))
				.as("an entry only says what there is to say")
				.isFalse();
		assertThat(admin.queryForObject(
				"SELECT is_not_bought FROM ingredients WHERE id = ?", Boolean.class, water)).isTrue();
	}

	@Test
	@DisplayName("the second recipe naming water finds the first one's Water, already marked, and disagrees with nothing")
	void theSecondRecipeFindsTheFirstOnesWater() throws Exception {
		// The case the seeding run is made of: 45 curated recipes into an empty temple, fifteen of
		// them naming water. The first creates it marked; the rest have to find that row and agree.
		importIt(curatedAkkiRotti());
		String second = importIt(curatedAkkiRotti("Neer Dose Curated"));

		assertThat(ingredientNames()).containsExactly("Rice flour", "Water");
		assertThat(isNotBought("Water")).isTrue();
		assertThat(importedAudit(second).has("notBoughtNotApplied")).isFalse();
		assertThat(auditCount("INGREDIENT_NOT_BOUGHT_CHANGED"))
				.as("one ingredient, one mark, however many recipes name it")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("two lines for one ingredient: a mark on either marks it, whichever comes first")
	void aMarkOnEitherLineMarksTheIngredient() throws Exception {
		// A book that marks water on one line and forgets it on another is making one statement
		// about water, not two about lines. Asserted with the UNMARKED line first, which is the
		// order a per-line implementation gets wrong.
		UUID master = insertLibraryRecipe("Twice Watered",
				line("Water", "5 L", "5", "L", null, false),
				line("Water", "9 L", "9", "L", "Hot", true));

		importIt(master);

		assertThat(ingredientNames()).containsExactly("Water");
		assertThat(isNotBought("Water")).isTrue();
	}

	@Test
	@DisplayName("a close match the person answers \"use ours\" is not marked either, and is reported")
	void aCloseMatchTheTempleUsesIsLeftAlone() throws Exception {
		// The temple's own row, close but not equal, chosen deliberately by a person on the copy
		// screen. Choosing it says which ingredient the line is; it does not say the temple has
		// stopped buying it.
		UUID theirs = insertIngredient("Water, filtered", "L");
		UUID master = insertLibraryRecipe("Curated Rasam",
				line("Water", "9 L", "9", "L", "Hot", true));

		String body = mvc.perform(authed(post("/api/v1/recipes/import/{id}", master))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"decisions": [
						  {"libraryName": "Water", "useIngredientId": "%s", "confirmDifferent": false}
						]}
						""".formatted(theirs)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String recipeId = JsonPath.read(body, "$.id");
		assertThat(ingredientNames()).containsExactly("Water, filtered");
		assertThat(admin.queryForObject(
				"SELECT is_not_bought FROM ingredients WHERE id = ?", Boolean.class, theirs)).isFalse();
		assertThat(importedAudit(recipeId).at("/notBoughtNotApplied").toString())
				.isEqualTo("[\"Water, filtered\"]");
	}

	@Test
	@DisplayName("a close match the person answers \"it's a different ingredient\" is created marked")
	void aCloseMatchConfirmedDifferentIsCreatedMarked() throws Exception {
		insertIngredient("Water, filtered", "L");
		UUID master = insertLibraryRecipe("Curated Rasam",
				line("Water", "9 L", "9", "L", "Hot", true));

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", master))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"decisions": [
						  {"libraryName": "Water", "useIngredientId": null, "confirmDifferent": true}
						]}
						"""))
				.andExpect(status().isCreated());

		// It is a row the import created, so the book's mark applies to it like any other creation.
		assertThat(isNotBought("Water")).isTrue();
		assertThat(isNotBought("Water, filtered")).isFalse();
		// And the override's own entry says so, in the shape IngredientService.snapshot writes.
		JsonNode added = JSON.readTree(admin.queryForObject("""
				SELECT after_state::text FROM audit_events
				WHERE action = 'INGREDIENT_ADDED' AND entity_id = ?
				""", String.class, ingredientId("Water")));
		assertThat(added.get("notBought").asBoolean()).isTrue();
		assertThat(added.get("supply").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("a Kitchen Manager copying the recipe cannot change the temple's own row, and can still copy it")
	void aKitchenManagerCopiesWithoutMakingPolicy() throws Exception {
		UUID water = insertIngredient("Water", "L");
		UUID master = curatedAkkiRotti();

		// MANAGE_RECIPES, not MANAGE_BUYING_POLICY. The copy goes through; the flag does not move.
		signIn("uid-manager-a");
		String recipeId = importIt(master);

		assertThat(admin.queryForObject(
				"SELECT is_not_bought FROM ingredients WHERE id = ?", Boolean.class, water)).isFalse();
		assertThat(importedAudit(recipeId).at("/notBoughtNotApplied").toString()).isEqualTo("[\"Water\"]");
		// The same person is refused the flag outright at its own endpoint, which is the rule this
		// import is being kept consistent with rather than quietly widening.
		mvc.perform(authed(patch("/api/v1/ingredients/{id}/not-bought", water))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"notBought\":true}"))
				.andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ and the point of all of it

	@Test
	@DisplayName("the shopping list for a meal cooking the imported recipe has no water on it")
	void theShoppingListHasNoWater() throws Exception {
		String recipeId = importIt(curatedAkkiRotti());
		UUID water = ingredientId("Water");
		UUID flour = ingredientId("Rice flour");

		// 200 servings of a recipe written for 10, with nothing on the shelf: both lines are a real
		// shortfall and both would be on the list but for the mark.
		LocalDate day = LocalDate.now(IST).plusDays(2);
		UUID meal = MealFixture.meal(admin, temple, day, "Breakfast", LocalTime.of(8, 0));
		MealFixture.dish(admin, temple, meal, UUID.fromString(recipeId),
				BigDecimal.valueOf(200), "PLANNED", staffId);

		assertThat(ids())
				.as("the ids on the list, read off the response rather than matched on a field")
				.containsExactly(flour);

		// Not vacuous: clear the mark through the Temple Admin's own endpoint and the water line
		// appears, from the same plan and the same empty shelf. So the absence above is the flag
		// doing its job and not a recipe that demanded nothing.
		setNotBought(water, false);
		assertThat(ids()).containsExactlyInAnyOrder(flour, water);
	}

	// ------------------------------------------------------------------ fixtures

	private record Line(String name, String qty, String qtyValue, String qtyUnit, String prep,
			Boolean notBought) {
	}

	private static Line line(String name, String qty, String qtyValue, String qtyUnit, String prep,
			boolean notBought) {
		return new Line(name, qty, qtyValue, qtyUnit, prep, notBought);
	}

	/** A line in the pre-T-403 shape: no {@code not_bought} key at all, as all 5,376 vendored rows were. */
	private static Line legacyLine(String name, String qty, String qtyValue, String qtyUnit) {
		return new Line(name, qty, qtyValue, qtyUnit, null, null);
	}

	/** One curated recipe: flour the temple buys, water it does not, the water line prepared "Hot". */
	private UUID curatedAkkiRotti() {
		return curatedAkkiRotti("Akki Rotti Curated");
	}

	private UUID curatedAkkiRotti(String name) {
		return insertLibraryRecipe(name,
				line("Rice flour", "12 Kg", "12", "KG", null, false),
				line("Water", "14 L", "14", "L", "Hot", true));
	}

	/**
	 * A library recipe written straight into the table in the shape the loader now writes: every line
	 * carries {@code prep} and {@code not_bought}, unless {@link #legacyLine} leaves them out.
	 */
	private UUID insertLibraryRecipe(String name, Line... lines) {
		StringBuilder json = new StringBuilder("[");
		StringBuilder names = new StringBuilder("{");
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				json.append(',');
				names.append(',');
			}
			json.append("{\"name\":\"").append(lines[i].name()).append("\",\"qty\":\"").append(lines[i].qty())
					.append("\",\"qtyValue\":").append(lines[i].qtyValue())
					.append(",\"qtyUnit\":\"").append(lines[i].qtyUnit()).append('"');
			if (lines[i].notBought() != null) {
				json.append(",\"prep\":")
						.append(lines[i].prep() == null ? "null" : "\"" + lines[i].prep() + "\"")
						.append(",\"not_bought\":").append(lines[i].notBought());
			}
			json.append('}');
			names.append('"').append(lines[i].name()).append('"');
		}
		json.append(']');
		names.append('}');
		String slug = name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
		return admin.queryForObject("""
				INSERT INTO master_recipes (state_slug, state, book_language, recipe_slug, name,
					display_name, category_key, category_name, badge, yield_text, yield_qty,
					yield_unit, why, ingredient_names, ingredients, method, source_ref)
				VALUES ('karnataka', 'Karnataka', 'English', ?, ?, ?, 'rice', 'Rice', 'Everyday',
					'10 Kg', 10, 'KG', 'because', CAST(? AS text[]), CAST(? AS jsonb), '["Mix."]'::jsonb, 'test')
				RETURNING id
				""", UUID.class, slug, name, name, names.toString(), json.toString());
	}

	private String importIt(UUID master) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	/** Each line's {@code not_bought} as jsonb text in array order: "true"/"false", or NULL if absent. */
	private List<String> storedMarks(UUID id) {
		return admin.queryForList("""
				SELECT (line -> 'not_bought')::text
				FROM master_recipes m,
				     LATERAL jsonb_array_elements(m.ingredients) WITH ORDINALITY AS t(line, n)
				WHERE m.id = ?
				ORDER BY t.n
				""", String.class, id);
	}

	/** The import's own audit entry, as JSON. */
	private JsonNode importedAudit(String recipeId) throws Exception {
		return JSON.readTree(admin.queryForObject("""
				SELECT after_state::text FROM audit_events
				WHERE action = 'RECIPE_IMPORTED' AND entity_id = ?::uuid
				""", String.class, recipeId));
	}

	private int auditCount(String action) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return n == null ? 0 : n;
	}

	private boolean isNotBought(String name) {
		return Boolean.TRUE.equals(admin.queryForObject(
				"SELECT is_not_bought FROM ingredients WHERE tenant_id = ? AND name = ?",
				Boolean.class, temple, name));
	}

	private UUID ingredientId(String name) {
		return admin.queryForObject(
				"SELECT id FROM ingredients WHERE tenant_id = ? AND name = ?", UUID.class, temple, name);
	}

	private String noteOn(String recipeId, String ingredientName) {
		return admin.queryForObject("""
				SELECT ri.preparation_note FROM recipe_ingredients ri
				JOIN ingredients i ON i.id = ri.ingredient_id
				WHERE ri.recipe_id = ?::uuid AND i.name = ?
				""", String.class, recipeId, ingredientName);
	}

	private List<String> ingredientNames() {
		return admin.queryForList(
				"SELECT name FROM ingredients WHERE tenant_id = ? ORDER BY name", String.class, temple);
	}

	private UUID insertIngredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', ?) RETURNING id
				""", UUID.class, temple, name, unit);
	}

	/** Through the real endpoint, as the Temple Admin, then back to whoever was signed in. */
	private void setNotBought(UUID ingredientId, boolean notBought) throws Exception {
		signIn("uid-admin-a");
		mvc.perform(authed(patch("/api/v1/ingredients/{id}/not-bought", ingredientId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"notBought\":%s}".formatted(notBought)))
				.andExpect(status().isNoContent());
	}

	/** The ingredient ids on the shopping list, read off the body rather than matched on a field. */
	private List<UUID> ids() throws Exception {
		String body = mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<UUID> out = new ArrayList<>();
		for (JsonNode line : JSON.readTree(body)) {
			out.add(UUID.fromString(line.get("ingredientId").asText()));
		}
		return out;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}
}
