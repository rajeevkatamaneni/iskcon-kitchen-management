package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.translation.RecipeTranslationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The job card (B5, rebuilt by build brief 2026-08-21 item 17): the sheet that goes to the kitchen
 * and comes back signed.
 *
 * <p>What is worth proving here is what the brief argued about. The worksheet has to carry a ruled
 * box for what was cooked and what was served, because the gap between those and the planned figure
 * is the reason the sheet comes back to the office at all. The card number has to stay a filing
 * reference rather than a heading. The worksheet has to stay English while the recipes follow the
 * printer's choice, and that choice has to be offered only in languages a translation actually
 * exists in. And the card has to be printable by a cook — a worksheet behind an administrator's
 * permission would mean asking somebody else for your own job sheet.
 *
 * <p>A mocked {@link Scheduler} keeps the request→enqueue path hermetic; the worker step is driven
 * synchronously through {@link DocumentGenerationService}, as the other document tests do.
 */
@AutoConfigureMockMvc
class JobCardIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private RecipeTranslationService recipeTranslationService;

	@Autowired
	private JobCardService jobCardService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;
	private UUID payasam;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		insertUser("uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");

		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Khichdi', ?, 100, 'KG', 'Wash the rice.
				Temper the spices.') RETURNING id
				""", UUID.class, tenant, category);
		// The same ingredient twice, as a real recipe does — once for the tempering, once for the
		// finish. The card must fold them into one line a cook can weigh out in one go.
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 3, 'KG', 0), (?, ?, ?, 2, 'KG', 1)
				""", tenant, khichdi, rice, tenant, khichdi, rice);

		// A second preparation, so that a meal can be part translated — which is the case the
		// appendix's rule exists for.
		payasam = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Payasam', ?, 100, 'KG', 'Boil the milk.') RETURNING id
				""", UUID.class, tenant, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 1, 'KG', 0)
				""", tenant, payasam, rice);

		admin.update("""
				INSERT INTO equipment_items (tenant_id, name, condition)
				VALUES (?, 'Wet grinder', 'NEEDS_REPAIR'), (?, 'Steam cauldron', 'GOOD')
				""", tenant, tenant);

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM document_label_translations");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM recipe_translations");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the printed card carries the meal, its scaled quantities and the recipes")
	void theCardCarriesTheMeal() throws Exception {
		plan("Lunch", 200, 200, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("Sri Sri Radha Govinda Temple")
				.contains("Lunch")
				.contains("Khichdi")
				// 3 KG + 2 KG per 100 servings, doubled for 200 — folded into one line, not two.
				// "10 Kg", not "10 KG": the card writes the unit the way a cook says it, and the
				// recipe card for this same line has always said "Kg" (E11-S5).
				.contains("10 Kg")
				.doesNotContain("6 Kg")
				.contains("200 adults")
				.contains("Wash the rice.");
	}

	@Test
	@DisplayName("one of a counted thing on the printed card reads \"1 piece\", not \"1 pieces\"")
	void oneOfACountedThingIsSingularOnThePrintedCard() throws Exception {
		// T-144. T-108 fixed this on the screens and could not reach the PDFs, so for one wave the
		// stock page said "1 piece" about a stool and the job card in the cook's hand said
		// "1 pieces" about the same stool on the same day. This is the printed half, asserted on the
		// real card rather than on the formatter — the formatter's own table is in QuantitiesTest.
		//
		// Two counted ingredients, because the plural must go on being right: fixing a plural by
		// making everything singular is the same defect facing the other way.
		countedIngredients();

		// The recipe's base yield is 100, so a plan for 100 scales 1:1 and the sheet prints the
		// figures as they are written on the recipe. That matters here: a fixture that never lands
		// on exactly one would pass whether this is fixed or not, because "2 pieces" was always
		// right.
		plan("Lunch", 100, 100, 0, 0);

		String html = print(null);

		assertThat(html)
				// The load-bearing one. "1 piece" on its own is a substring of "1 pieces" and would
				// pass with the defect in place, which is why the negative is here beside it.
				.contains("1 piece")
				.doesNotContain("1 pieces")
				// Unchanged by this work, and asserted so that it stays unchanged.
				.contains("3 pieces");
	}

	@Test
	@DisplayName("a counted line that scales down to one is singular too, because the card rounds first")
	void aCountedLineThatRoundsToOneIsSingular() throws Exception {
		countedIngredients();

		// 70 of a 100-serving recipe: the single banana becomes 0.7 of one and the three become 2.1.
		// A cook cannot fetch 0.7 of a banana, so the card rounds a count to a whole thing before it
		// prints it — and the word has to be chosen from the figure that is actually printed, not
		// from the one that was scaled. This is the non-integer case, and it is the reason the word
		// is picked inside Quantities.say() rather than by any of its callers.
		plan("Lunch", 70, 70, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("1 piece")
				.doesNotContain("1 pieces")
				.contains("2 pieces");
	}

	@Test
	@DisplayName("the card prints a line's preparation note, and keeps two preparations of one thing apart (R-DUP-1)")
	void theCardPrintsPreparationNotes() throws Exception {
		UUID chilli = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Green chilli', 'Produce', 'KG') RETURNING id
				""", UUID.class, tenant);
		// Slit twice (folded into one line, as repeated lines always were) and chopped once (a
		// different job at the chopping board, so a line of its own).
		admin.update("""
				INSERT INTO recipe_ingredients
					(tenant_id, recipe_id, ingredient_id, quantity, unit, line_order, preparation_note)
				VALUES (?, ?, ?, 1, 'KG', 2, 'slit'), (?, ?, ?, 1, 'KG', 3, 'slit'),
					   (?, ?, ?, 3, 'KG', 4, 'chopped')
				""", tenant, khichdi, chilli, tenant, khichdi, chilli, tenant, khichdi, chilli);

		plan("Lunch", 100, 100, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("<tr><td>Green chilli · slit</td><td class=\"num\">2 Kg</td></tr>")
				.contains("<tr><td>Green chilli · chopped</td><td class=\"num\">3 Kg</td></tr>")
				// The rice lines have no note, and still fold into one line with no separator.
				.contains("<tr><td>Rice</td><td class=\"num\">5 Kg</td></tr>")
				.doesNotContain("Green chilli · slit</td><td class=\"num\">1 Kg");
	}

	@Test
	@DisplayName("the translated appendix prints the preparation note translated too (R-DUP-1)")
	void theTranslatedCardTranslatesTheNote() throws Exception {
		UUID chilli = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Green chilli', 'Produce', 'KG') RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO recipe_ingredients
					(tenant_id, recipe_id, ingredient_id, quantity, unit, line_order, preparation_note)
				VALUES (?, ?, ?, 1, 'KG', 2, 'slit')
				""", tenant, khichdi, chilli);

		plan("Lunch", 100, 100, 0, 0);

		assertThat(print("kn")).contains("[kn] Green chilli · [kn] slit");
	}

	/**
	 * A banana and three cardamom pods on the Khichdi — one counted line of exactly one, one of more
	 * than one.
	 *
	 * <p>Seeded inside the tests that want them rather than in {@code @BeforeEach}, so that the two
	 * dozen assertions about the card's layout above go on reading the fixture they were written
	 * against. {@code tearDown} already clears {@code recipe_ingredients} and {@code ingredients}.
	 */
	private void countedIngredients() {
		UUID banana = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Banana', 'Produce', 'PIECES') RETURNING id
				""", UUID.class, tenant);
		UUID cardamom = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Cardamom pod', 'Spices', 'PIECES') RETURNING id
				""", UUID.class, tenant);

		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 1, 'PIECES', 2), (?, ?, ?, 3, 'PIECES', 3)
				""", tenant, khichdi, banana, tenant, khichdi, cardamom);
	}

	@Test
	@DisplayName("the corner says which meal, on two lines, and the card number is not up there at all")
	void theHeaderLeadsWithTheMealAndNotTheCardNumber() throws Exception {
		plan("Dinner", 133, 133, 0, 0);

		String html = print(null, "Dinner");

		// Two lines, because one line of "Outside Event: Bhagavad Gita Parayanam · Saturday 5
		// September 2026" is a line nobody reads the end of (Rajeev, 2026-09-05).
		assertThat(html).contains("<div class=\"meal\">Dinner</div>");
		assertThat(html).contains("<div class=\"date\">Monday 17 March 2025</div>");

		// The number left the corner entirely: "It is taking up prime real estate and it does not
		// belong there." It lives in the running footer now, and nowhere else on the sheet.
		assertThat(html).doesNotContain("class=\"card-no\"><");
		assertThat(html).contains("DC-2025-0001");

		// The occasion is gone unconditionally. The people holding this sheet know what day it is.
		assertThat(html).doesNotContain("Occasion");

		// The emblem is inlined, because the renderer has no network and a linked image would print
		// as a broken box.
		assertThat(html).contains("<svg").contains("ISKCON lotus emblem");
	}

	@Test
	@DisplayName("an outside event names its kind and its own name, on the first line")
	void anOutsideEventNamesBoth() throws Exception {
		planEvent("Bhagavad Gita Parayanam", 40);

		String html = print(null, "Event", "Bhagavad Gita Parayanam");

		// Rajeev asked for exactly this shape on 2026-09-05: the kind says what shape of thing this
		// is, the name says which one, and a folder of Saturdays needs both to tell them apart.
		assertThat(html).contains("<div class=\"meal\">Outside Event: Bhagavad Gita Parayanam</div>");
	}

	@Test
	@DisplayName("cooked is on the worksheet and served is on the serving sheet, one signature each")
	void cookedAndServedAreOnDifferentSheets() throws Exception {
		plan("Lunch", khichdi, 133, 133, 0, 0);
		plan("Lunch", payasam, 133, 133, 0, 0);

		String html = print(null);

		// Renamed 2026-09-05: "Servings" named the numbers in it rather than the thing it is.
		assertThat(html)
				.contains("Food items to prepare")
				.doesNotContain("<h2>Servings</h2>")
				.contains("<span class=\"name\">Khichdi</span>")
				.contains("<span class=\"name\">Payasam</span>");

		// Split so that each signature covers only what that person actually saw: the kitchen knows
		// what came out of the pot, the servers know what left the counter.
		int worksheet = html.indexOf("Food items to prepare");
		int serving = html.indexOf("Serving sheet");
		assertThat(worksheet).isLessThan(serving);
		assertThat(html.indexOf("<th class=\"pen\">Cooked</th>")).isBetween(worksheet, serving);
		assertThat(html.indexOf("<th class=\"pen\">Served</th>")).isGreaterThan(serving);

		// Two preparations, one pen box each, on each of the two sheets.
		assertThat(countOf(html, "<td class=\"pen\"><span class=\"box\"></span></td>")).isEqualTo(4);

		// The serving sheet starts a page of its own. Whitespace at the foot of the worksheet is the
		// point, not waste.
		assertThat(html).contains("section.break{break-before:page}");
	}

	@Test
	@DisplayName("two people sign, not three: the cooked figures were checked and the served ones recorded")
	void twoSignatureBoxes() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("Kitchen manager / head cook")
				.contains("The cooked figures above were checked.")
				.contains("Serving staff")
				.contains("The served figures above were recorded.")
				// The old three asked for a name against a moment nobody is separately responsible for.
				.doesNotContain("Cooked by")
				.doesNotContain("Checked by")
				.doesNotContain("Served by");
		// Two on the worksheet and two on the serving sheet, since the pack split in two.
		assertThat(countOf(html, "class=\"sign\"")).isEqualTo(4);
	}

	@Test
	@DisplayName("who is on carries a count and a number to ring")
	void whoIsOnCarriesPhoneNumbers() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		rosterStaffOnTheDay();

		String html = print(null);

		// The one thing this sheet is asked for at 05:40 is a way to ring whoever has not arrived.
		assertThat(html)
				// Renamed 2026-09-05. "Who is on" reads like a rota; these are the people this card
				// is being worked by.
				.contains("People working on this job card")
				.doesNotContain("Who is on")
				.contains("<h3>Staff · 1</h3>")
				.contains("Gopal Das")
				.contains("+919876500081")
				.contains("<h3>Volunteers · 0</h3>");
	}

	@Test
	@DisplayName("the planned crew prints above the names, and leaves no gap when nobody set one")
	void thePlannedCrewPrintsAboveTheNames() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// A meal is planned weeks before anybody is rostered, so having no figure is ordinary. The
		// card is not the place to print a blank where a decision has not been taken.
		assertThat(print(null)).doesNotContain("Planned crew");

		admin.update("UPDATE meals SET crew_required = 8 WHERE tenant_id = ?", tenant);
		String html = print(null);
		assertThat(html).contains("Planned crew · 8 people");
		assertThat(html.indexOf("Planned crew")).isLessThan(html.indexOf("<h3>Staff"));
	}

	@Test
	@DisplayName("a card number is issued once, kept across reprints, and never repeats within a temple")
	void cardNumbersAreStableAndMonotonic() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		plan("Breakfast", 60, 60, 0, 0);

		String lunch = cardNumber("Lunch");
		assertThat(lunch).isEqualTo("LC-2025-0001");

		// A reprint of the same meal is the same sheet.
		assertThat(cardNumber("Lunch")).isEqualTo(lunch);
		assertThat(print(null)).contains(lunch);

		// A different meal takes the next number in the temple's own sequence, with its own prefix.
		assertThat(cardNumber("Breakfast")).isEqualTo("BC-2025-0002");
	}

	@Test
	@DisplayName("the worksheet stays English while the recipes print in the language that was asked for")
	void theWorksheetIsEnglishAndTheRecipesAreNot() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		translateRecipe(khichdi, "kn");

		String kannada = print("kn");

		// Two halves, two readers (Q3). The office reads the worksheet and files it, so it is English
		// — the app's own UI is English-only in Phase 1. The cooks read the recipes.
		assertThat(kannada)
				.contains("<th>Preparation</th>")
				.contains("<span class=\"name\">Khichdi</span>")
				.contains("Kitchen manager / head cook")
				.contains("[kn] Khichdi")
				.contains("[kn] Rice")
				.contains("[kn] Ingredient")
				// Numbers, times and the card number are never translated.
				.contains("5 Kg")
				.contains("LC-2025-0001");

		// English asks nothing of the translator at all.
		assertThat(print("en")).contains("Khichdi").doesNotContain("[kn]");
	}

	@Test
	@DisplayName("every language is offered, because which one a cook reads is not a fact about the temple")
	void everyLanguageIsOffered() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// This list used to hold only the languages a translation already existed in, which made the
		// picker on a temple that had never translated anything hold one entry and look broken. The
		// premise underneath it was worse: that a temple's cooks read the language of the state it
		// stands in. A Bengaluru kitchen with three Odia cooks is the ordinary case. So the whole
		// choice is offered and the translation is produced when a card is asked for.
		languages()
				.andExpect(jsonPath("$.languages.length()").value(23))
				.andExpect(jsonPath("$.languages[0]").value("en"))
				.andExpect(jsonPath("$.defaultLanguage").value("en"));

		// And it does not change as translations come and go — the offer is not a report on what
		// happens to be cached.
		translateRecipe(khichdi, "kn");
		languages().andExpect(jsonPath("$.languages.length()").value(23));

		admin.update("UPDATE recipes SET version = version + 1 WHERE id = ?", khichdi);
		languages().andExpect(jsonPath("$.languages.length()").value(23));
	}

	@Test
	@DisplayName("a temple that works in Kannada gets Kannada recipes without asking, once it has them")
	void defaultsToTheTemplesOwnLanguage() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// A cook prints the card but does not decide what language the temple works in — that is a
		// temple-settings act, and the split is the ordinary one.
		setLanguage("kn").andExpect(status().isForbidden());

		// The setting had existed since V1 and been unwritable, so every temple was quietly English.
		// The card is the first thing to read it, which is what made it worth being able to set.
		signIn("uid-admin-a");
		setLanguage("kn").andExpect(status().isNoContent());
		signIn("uid-staff-a");

		// The temple's own language is the default, not the rule (Q3). It used to be narrowed to what
		// had already been translated, so a temple that had just set Kannada was still shown English
		// until somebody translated something. Nothing has to be translated in advance now.
		languages().andExpect(jsonPath("$.defaultLanguage").value("kn"));

		translateRecipe(khichdi, "kn");
		languages().andExpect(jsonPath("$.defaultLanguage").value("kn"));

		// Nobody chose at the printer, and the recipes still come out in the language the kitchen
		// reads — which is the whole point of them going to the kitchen.
		assertThat(print(null)).contains("[kn] Khichdi");

		// And English is still one choice away, for the head cook who wants it.
		assertThat(print("en")).contains("Khichdi").doesNotContain("[kn]");
	}

	@Test
	@DisplayName("a preparation nobody had translated is translated when the card is asked for")
	void anUntranslatedPreparationIsTranslatedOnDemand() throws Exception {
		plan("Lunch", khichdi, 100, 100, 0, 0);
		plan("Lunch", payasam, 100, 100, 0, 0);
		translateRecipe(khichdi, "kn");

		String html = print("kn");

		// Khichdi was translated before; Payasam was not, and is translated now rather than being
		// printed in English under an apology. Nothing on the card says "not translated yet",
		// because nothing on it is.
		assertThat(html)
				.contains("[kn] Khichdi")
				.contains("[kn] Payasam");
		assertThat(countOf(html, "class=\"untranslated\"")).isZero();
	}

	@Test
	@DisplayName("the recipes are optional; the worksheet prints on its own when they are not wanted")
	void theRecipesAreOptional() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		String worksheetOnly = print("none");

		// The worksheet is unchanged; the appendix simply is not there.
		assertThat(worksheetOnly)
				.contains("<th>Preparation</th>")
				.contains("<span class=\"name\">Khichdi</span>")
				.doesNotContain("Wash the rice.");

		// Asked for, they come back — and they start their own page rather than being woven through
		// the sheet that goes back to the office.
		assertThat(print("en")).contains("<span class=\"sheet-title\">Recipes").contains("Wash the rice.");
	}

	@Test
	@DisplayName("the print window previews as an A4 page rather than going edge to edge")
	void theScreenPreviewLooksLikeThePrint() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// The PDF was always right — @page carries the margin. The window the Print button opens had
		// no page of its own, so the same file was two different sheets.
		assertThat(print(null))
				.contains("@page{size:A4;margin:14mm}")
				.contains("@media screen{")
				.contains("width:210mm;margin:8mm auto;padding:14mm");
	}

	@Test
	@DisplayName("the font stack names a family per script rather than trusting codepoint fallback")
	void theFontStackNamesEveryScriptTheImageHas() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// Telugu and Tamil reached the page through Chromium's own fallback, which picks whatever
		// fontconfig ranks first and can change when the base image does.
		assertThat(print(null))
				.contains("'Noto Sans Devanagari'")
				.contains("'Noto Sans Kannada'")
				.contains("'Noto Sans Telugu'")
				.contains("'Noto Sans Tamil'")
				.contains("'Noto Sans Bengali'")
				.contains("'Noto Sans Malayalam'");
	}

	@Test
	@DisplayName("a cook can print their own job sheet; a volunteer cannot")
	void printableByTheKitchenAndNotByAVolunteer() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", mealIdFor("Lunch").toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"));

		signIn("uid-vol-a");
		mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", mealIdFor("Lunch").toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a requested PDF is versioned, generated and downloadable")
	void thePdfIsGeneratedAndDownloadable() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		String body = mvc.perform(post("/api/v1/job-cards")
						.param("mealId", mealIdFor("Lunch").toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.cardNumber").value("LC-2025-0001"))
				.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(
				body.replaceAll(".*\"documentId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

		TenantContext.set(tenant);
		try {
			generationService.generate(documentId);
		} finally {
			TenantContext.clear();
		}

		mvc.perform(get("/api/v1/job-cards/documents/{id}", documentId)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.version").value(1));

		mvc.perform(get("/api/v1/job-cards/documents/{id}/download", documentId)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"));

		// A reprint after a dish was swapped is a different sheet, so it is a new version — but the
		// same card number, because it is still the same meal.
		mvc.perform(post("/api/v1/job-cards")
						.param("mealId", mealIdFor("Lunch").toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.cardNumber").value("LC-2025-0001"));

		mvc.perform(get("/api/v1/job-cards/documents")
						.param("mealId", mealIdFor("Lunch").toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].version").value(2));
	}

	@Test
	@DisplayName("a meal nobody planned has no card, and no number is spent on it")
	void nothingPlannedMeansNoCard() throws Exception {
		// A meal id nothing answers to — the one way to ask for a card for a meal nobody planned, now that
		// a card is asked for by the meal's own id (D-27).
		mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", UUID.randomUUID().toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNotFound());

		assertThat(admin.queryForObject("SELECT count(*) FROM meal_card_sequence", Integer.class))
				.isZero();
	}

	@Test
	@DisplayName("the delivery details are on their own sheet, not on the one the kitchen cooks from")
	void theDeliverySheetIsItsOwnPage() throws Exception {
		planEvent("Bhagavad Gita Parayanam", 40);

		String html = print(null, "Event", "Bhagavad Gita Parayanam");

		// Rajeev, 2026-09-05: "Why do we have For and Going to in the most important part of the job
		// card." They are on the last sheet now, which is also the only one that leaves the building.
		int worksheet = html.indexOf("Food items to prepare");
		int delivery = html.indexOf("Delivery sheet");
		assertThat(worksheet).isLessThan(delivery);
		assertThat(html.indexOf("Mrs Latha Rao")).isGreaterThan(delivery);
		assertThat(html.indexOf("Mantri Serenity")).isGreaterThan(delivery);

		// The sub-premise is kept and printed, never folded into the address: the van is routed to
		// the gate, and this is what the driver asks about when they get there.
		assertThat(html).contains("<div class=\"sub-location\">Clubhouse</div>");

		// The figure is the temple's own — 45 minutes, set by hand — so the leave-by is 12:15 and the
		// sheet says whose number it is.
		assertThat(html)
				.contains("<dd>12:15</dd>")
				.contains("<dd>45 minutes</dd>")
				.contains("Set by hand at the temple, not by the map service.");

		// And the deliver-by is on the worksheet's fact strip, where the kitchen sees it.
		assertThat(html.indexOf("Deliver by")).isLessThan(delivery);
	}

	@Test
	@DisplayName("an in-house meal has no delivery sheet at all")
	void nothingLeavingMeansNoDeliverySheet() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		assertThat(print(null))
				.doesNotContain("Delivery sheet")
				.doesNotContain("Deliver by");
	}

	@Test
	@DisplayName("the version moves when the meal changes and holds still when it does not")
	void theVersionTracksTheMealAndNotThePrinting() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// Rajeev, 2026-09-05: "The lastest version number must be the correct one." So the first
		// print is v1, and printing it again is the same sheet — two sheets reading v1 ARE the same
		// sheet, and nobody hunts for a difference that is not there.
		assertThat(print(null)).contains("v1 · printed");
		assertThat(print(null)).contains("v1 · printed");

		// A late change to the meal, and the next sheet says so.
		admin.update("UPDATE meals SET kitchen_notes = 'Less chilli' WHERE tenant_id = ?", tenant);
		assertThat(print(null)).contains("v2 · printed");

		// The appendix is a choice made at the printer, not a change to the meal.
		assertThat(print("en")).contains("v2 · printed");
	}

	@Test
	@DisplayName("a swapped roster does not make a new version of a card that cooks the same food")
	void theRosterDoesNotBumpTheVersion() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		assertThat(print(null)).contains("v1 · printed");

		rosterStaffOnTheDay();

		// The roster moves daily from the staff schedule. A card whose cooking instructions are
		// identical must not climb to v9 because three volunteers swapped shifts.
		String html = print(null);
		assertThat(html).contains("v1 · printed").contains("Gopal Das");
	}

	@Test
	@DisplayName("every page carries the card number and the version, and the PDF gets a page number")
	void everyPageIdentifiesItself() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// The print view draws its own footer, fixed, so Chromium repeats it on every sheet.
		String html = print(null);
		assertThat(html)
				.contains("footer.running{position:fixed")
				.contains("<span class=\"card-no\">LC-2025-0001</span>");

		// The PDF cannot: Blink implements neither @page margin boxes nor counter(page), so the page
		// number can only come from the renderer's own footer — which means the document leaves its
		// footer out and hands the words over instead.
		JobCardService.RenderedCard card = asTenant(() ->
				jobCardService.renderForPdf(mealIdFor("Lunch"), null));
		assertThat(card.html()).doesNotContain("footer class=\"running\"");
		assertThat(card.footer().left()).startsWith("v1 · printed");
		assertThat(card.footer().right()).isEqualTo("LC-2025-0001");
	}

	@Test
	@DisplayName("a fasting day says so on the card, in the words a cook needs")
	void aFastingDayIsOnTheCard() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		admin.update("""
				INSERT INTO calendar_days (tenant_id, cal_date, tithi, paksa, masa, is_ekadashi,
						ekadashi_name, fast_type)
				VALUES (?, DATE '2025-03-17', 10, 1, 11, true, 'Papamocani Ekadasi', 'Ekadashi')
				""", tenant);

		// Not a badge, and not a colour: a cook reading this at speed in a hot room needs the sentence.
		// The engine stores GCAL's "Ekadasi"; the card prints the app's one spelling (2026-09-18).
		assertThat(print(null))
				.contains("Papamocani Ekadashi")
				.doesNotContain("Ekadasi")
				.contains("No grains, dal or beans");
	}

	@Test
	@DisplayName("equipment is on neither the print view nor the PDF, even when the temple has some")
	void equipmentIsNotOnTheCard() throws Exception {
		// The fixture registers a wet grinder that needs repair and a steam cauldron in good order, so
		// the temple has equipment and the old section would have printed. Rajeev, 2026-09-14: "The
		// Kitchen staff know about their equipment better than ANY APP or Job card will ever know."
		plan("Lunch", 100, 100, 0, 0);

		assertThat(print(null))
				.doesNotContain("Equipment")
				.doesNotContain("Wet grinder")
				.doesNotContain("Steam cauldron");

		// The PDF is rendered from the same template by a different entry point, so it is asserted on
		// its own rather than taken on trust.
		JobCardService.RenderedCard card = asTenant(() ->
				jobCardService.renderForPdf(mealIdFor("Lunch"), null));
		assertThat(card.html())
				.doesNotContain("Equipment")
				.doesNotContain("Wet grinder")
				.doesNotContain("Steam cauldron");
	}

	@Test
	@DisplayName("a card printed before equipment left the fingerprint keeps its version; a changed meal still moves")
	void aCardPrintedBeforeEquipmentLeftKeepsItsVersion() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		assertThat(print(null)).contains("v1 · printed");
		UUID meal = mealIdFor("Lunch");

		// Put the meal back the way the code before 2026-09-14 left it: its stored fingerprint in the
		// old format, which hashed the temple's equipment list in beside the facts about the meal.
		// The list is spelled out rather than read from the service, because the old wording and
		// order — broken first, condition in brackets — is exactly what the legacy match has to agree
		// with.
		String legacy = JobCardService.fingerprint(
				asTenant(() -> jobCardService.build(meal, null, true)),
				List.of("Wet grinder (needs repair)", "Steam cauldron"));
		admin.update("UPDATE meals SET card_fingerprint = ? WHERE id = ?", legacy, meal);

		// Nothing about the meal changed, only how its fingerprint is worked out, so the kitchen's v1
		// sheet is still the current one.
		assertThat(print(null)).contains("v1 · printed");

		// And the stored value was moved to the new format as it was matched, so the legacy path is
		// not taken again for this meal.
		String stored = admin.queryForObject(
				"SELECT card_fingerprint FROM meals WHERE id = ?", String.class, meal);
		assertThat(stored).isNotEqualTo(legacy);
		assertThat(admin.queryForObject("SELECT card_version FROM meals WHERE id = ?", Integer.class, meal))
				.isEqualTo(1);
		assertThat(print(null)).contains("v1 · printed");

		// A meal that really changed while its card was in the old format still moves on: the legacy
		// fingerprint is taken of the meal as it is now, so it no longer matches either.
		admin.update("UPDATE meals SET card_fingerprint = ? WHERE id = ?", legacy, meal);
		admin.update("UPDATE meals SET kitchen_notes = 'Less chilli' WHERE id = ?", meal);
		assertThat(print(null)).contains("v2 · printed");
	}

	// ---------------------------------------------------------------------

	private void plan(String kind, int servings, int adults, int children, int seniors) {
		plan(kind, khichdi, servings, adults, children, seniors);
	}

	/** One dish of the day's meal of this kind, found or created, with the meal's head count. */
	private void plan(String kind, UUID recipe, int servings, int adults, int children, int seniors) {
		UUID meal = MealFixture.meal(admin, tenant, DAY, kind, LocalTime.NOON);
		MealFixture.headCount(admin, meal, adults, children, seniors);
		MealFixture.dish(admin, tenant, meal, recipe, BigDecimal.valueOf(servings), staff());
	}

	private static final LocalDate DAY = LocalDate.of(2025, 3, 17);

	private UUID staff() {
		return admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'", UUID.class);
	}

	/**
	 * An outside delivery, planned directly the way {@link #plan} does.
	 *
	 * <p>Through SQL rather than the endpoint because the endpoint's own rules — a contact, a
	 * handover, a serving time — are E4-S15's to test, and this is about what the card does with the
	 * row once it exists.
	 */
	private void planEvent(String eventName, int servings) {
		UUID meal = MealFixture.meal(admin, tenant, DAY, "Event", eventName, LocalTime.of(11, 0));
		MealFixture.headCount(admin, meal, servings, 0, 0);
		admin.update("""
				UPDATE meals
				SET is_outside = true, handover = 'DELIVERY', contact_name = 'Mrs Latha Rao',
					contact_phone = '+919000000001',
					delivery_address = 'Mantri Serenity, Kanakapura Main Rd, Bengaluru 560062',
					delivery_sub_location = 'Clubhouse', guests_eat_at = TIME '13:00',
					travel_minutes = 45, travel_minutes_source = 'MANUAL'
				WHERE id = ?
				""", meal);
		MealFixture.dish(admin, tenant, meal, khichdi, BigDecimal.valueOf(servings), staff());
	}

	/**
	 * Stores a translation the way the app does — through the recipe translation service, which is
	 * the only thing that ever writes {@code recipe_translations}. The card reads what is there and
	 * never asks for a translation of its own, so a test that inserted a row by hand would be
	 * testing a table rather than the rule.
	 */
	private void translateRecipe(UUID recipeId, String language) {
		TenantContext.set(tenant);
		try {
			recipeTranslationService.translate(recipeId, language);
		} finally {
			TenantContext.clear();
		}
	}

	/**
	 * Runs something as the tenant, the way a request does.
	 *
	 * <p>Every service in this application reads its rows through Row-Level Security, and a test
	 * thread with no tenant set sees none of them — which surfaces as KMS-400030, not as a hint that
	 * the context is missing.
	 */
	private <T> T asTenant(java.util.function.Supplier<T> work) {
		TenantContext.set(tenant);
		try {
			return work.get();
		} finally {
			TenantContext.clear();
		}
	}

	/** The day's meal of this kind, by its own row (D-27) — it exists from the moment it is planned. */
	private UUID mealIdFor(String mealKind) {
		return mealIdFor(mealKind, null);
	}

	private UUID mealIdFor(String mealKind, String eventName) {
		return admin.queryForObject("""
				SELECT m.id FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				JOIN meal_kinds k ON k.id = m.meal_kind_id
				WHERE m.tenant_id = ? AND pd.plan_date = DATE '2025-03-17' AND k.name = ?
				  AND lower(COALESCE(m.event_name, '')) = lower(COALESCE(CAST(? AS text), ''))
				""", UUID.class, tenant, mealKind, eventName);
	}

	private String print(String language, String mealKind) throws Exception {
		return print(language, mealKind, null);
	}

	/** An event is its own meal, with its own card — found here by its name, printed by its id. */
	private String print(String language, String mealKind, String eventName) throws Exception {
		var request = get("/api/v1/job-cards/print")
				.param("mealId", mealIdFor(mealKind, eventName).toString())
				.header("Authorization", "Bearer valid-token");
		if (language != null) {
			request = request.param("language", language);
		}
		return mvc.perform(request).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private String print(String language) throws Exception {
		return print(language, "Lunch");
	}

	private org.springframework.test.web.servlet.ResultActions languages() throws Exception {
		return mvc.perform(get("/api/v1/job-cards/languages")
				.param("mealId", mealIdFor("Lunch").toString())
				.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk());
	}

	/**
	 * Rosters the one staff member on the day the card is printed for, through the template the week
	 * grid reads — the card must agree with the grid rather than hold a second opinion of the roster.
	 */
	private void rosterStaffOnTheDay() {
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (tenant_id, user_id, full_name, phone, job_title,
						employment_type, employment_status, date_of_joining)
				VALUES (?, (SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'), 'Gopal Das',
						'+919876500081', 'HEAD_COOK', 'FULL_TIME', 'ACTIVE', DATE '2024-01-01')
				RETURNING id
				""", UUID.class, tenant);
		// 2025-03-17 is a Monday.
		admin.update("""
				INSERT INTO staff_schedule_template (tenant_id, staff_profile_id, day_of_week, working,
						start_time, end_time)
				VALUES (?, ?, 1, true, TIME '06:00', TIME '14:00')
				""", tenant, profile);
	}

	private static int countOf(String haystack, String needle) {
		int count = 0;
		int at = haystack.indexOf(needle);
		while (at >= 0) {
			count++;
			at = haystack.indexOf(needle, at + needle.length());
		}
		return count;
	}

	private String cardNumber(String mealKind) throws Exception {
		String body = mvc.perform(post("/api/v1/job-cards")
						.param("mealId", mealIdFor(mealKind).toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"cardNumber\"\\s*:\\s*\"([^\"]+)\".*", "$1");
	}

	private org.springframework.test.web.servlet.ResultActions setLanguage(String language) throws Exception {
		return mvc.perform(put("/api/v1/settings/language")
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"language\":\"" + language + "\"}"));
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
	}

}
