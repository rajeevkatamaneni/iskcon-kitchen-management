package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Document generation and download (E2-S5), the worker-side core plus the HTTP surface — without a
 * scheduler (the async request→job path is {@link RecipeDocumentE2EIT}). Uses the default stub
 * renderer + local storage, so it is fully hermetic.
 */
@AutoConfigureMockMvc
class DocumentGenerationIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID recipe;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@govinda.example', '+919876500090', 'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Rice', false) RETURNING id
				""", UUID.class, temple);
		UUID rice = insertIngredient("Rice");
		UUID dal = insertIngredient("Toor Dal");
		recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Khichdi', ?, 100, 'KG', 'Wash the rice.
				Cook rice and dal together until soft.') RETURNING id
				""", UUID.class, temple, category);
		insertLine(rice, "2", "KG", 0);
		insertLine(dal, "1", "KG", 1);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a pending document is rendered, stored, marked READY, and downloadable as a PDF")
	void generatesAndDownloads() throws Exception {
		UUID doc = insertPendingDocument(null);

		generateWithin(doc);

		Map<String, Object> row = admin.queryForMap("SELECT status, storage_key FROM documents WHERE id = ?", doc);
		assertThat(row.get("status")).isEqualTo("READY");
		assertThat(row.get("storage_key")).isNotNull();

		stubVerifier.accept("uid-admin");
		mvc.perform(get("/api/v1/documents/{id}", doc).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"));

		byte[] pdf = mvc.perform(get("/api/v1/documents/{id}/download", doc)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.header().string("Content-Type", "application/pdf"))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 8)).isEqualTo("%PDF-1.4");
	}

	@Test
	@DisplayName("a scaled document renders at the requested yield")
	void generatesScaled() {
		UUID doc = insertPendingDocument(new java.math.BigDecimal("300"));
		generateWithin(doc);
		assertThat(admin.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, doc))
				.isEqualTo("READY");
	}

	@Test
	@DisplayName("a bad request is recorded as FAILED with a reason, not left pending")
	void recordsFailure() {
		// A target yield beyond the cap makes scaling reject it; the failure lands on the row.
		UUID doc = insertPendingDocument(new java.math.BigDecimal("99999"));
		generateWithin(doc);
		Map<String, Object> row = admin.queryForMap("SELECT status, error FROM documents WHERE id = ?", doc);
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isNotNull();
	}

	@Test
	@DisplayName("download is refused until the document is READY")
	void downloadPendingIsNotFound() throws Exception {
		UUID doc = insertPendingDocument(null);
		stubVerifier.accept("uid-admin");
		mvc.perform(get("/api/v1/documents/{id}/download", doc).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("the recipe PDF prints a line's preparation note after its name, at base and scaled (R-DUP-1)")
	void thePdfPrintsThePreparationNote() {
		UUID chilli = insertIngredient("Green chilli");
		insertLine(chilli, "0.5", "KG", 2);
		admin.update("UPDATE recipe_ingredients SET preparation_note = 'slit' WHERE ingredient_id = ?", chilli);

		TenantContext.set(temple);
		try {
			String base = RecipeCardTemplate.render(generationService.buildModel(recipe, null, "en"));
			String scaled = RecipeCardTemplate.render(
					generationService.buildModel(recipe, new java.math.BigDecimal("200"), "en"));

			assertThat(base).contains("<td>Green chilli · slit</td><td class=\"amt\">500 gm</td>");
			assertThat(scaled).contains("<td>Green chilli · slit</td><td class=\"amt\">1 Kg</td>");
			// A line with no note prints its name alone — no dangling separator.
			assertThat(base).contains("<td>Rice</td>").doesNotContain("Rice ·");
		} finally {
			TenantContext.clear();
		}
	}

	/**
	 * One unit for the scaled yield and the base it was scaled from (T-364).
	 *
	 * <p>The two figures are one sentence, read in one glance, and they were asked for one at a time,
	 * so a card made from a base of half a litre printed "Scaled to 2 L (base 500 ml)" — four times
	 * the recipe, written so that nobody can see it is four times the recipe without converting
	 * first. The unit is still printed twice, which was always right; what was wrong was printing two
	 * different ones.
	 */
	@Test
	@DisplayName("the recipe card says a scaled yield and its base in one unit: 2 L from 0.5 L, not from 500 ml")
	void theScaledYieldAndItsBaseShareAUnit() {
		UUID jaggery = insertIngredient("Jaggery water");
		UUID panakam = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Panakam', (SELECT id FROM recipe_categories WHERE tenant_id = ?), 0.5, 'L', 'Stir.')
				RETURNING id
				""", UUID.class, temple, temple);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 0.4, 'L', 0)
				""", temple, panakam, jaggery);

		TenantContext.set(temple);
		try {
			String scaled = RecipeCardTemplate.render(
					generationService.buildModel(panakam, new java.math.BigDecimal("2"), "en"));
			String base = RecipeCardTemplate.render(generationService.buildModel(panakam, null, "en"));

			assertThat(scaled)
					.contains("<div class=\"yield\">Scaled to 2 L (base 0.5 L)</div>")
					.doesNotContain("500 ml");
			// An unscaled card says one figure and has no pair to agree with, so it is unchanged: a
			// lone half-litre is still said the way a person says it.
			assertThat(base).contains("<div class=\"yield\">Yields 500 ml</div>");
		} finally {
			TenantContext.clear();
		}
	}

	/**
	 * The ingredient column is deliberately <em>not</em> given one unit (T-364), and this is the test
	 * that says so out loud rather than leaving it to be "fixed" by the next person who reads the
	 * one-row-one-unit rule and applies it everywhere.
	 *
	 * <p>The rule is about figures read <em>together</em>: a total against the lots it is drawn from,
	 * a shortfall pair, a yield against its base. An ingredient column is not that. Nobody sums it,
	 * nobody subtracts within it, and its rows are not even of one family — a card can hold
	 * kilograms, litres and pieces in the same column. Each row is a separate instruction to weigh
	 * one thing, and the unit that makes that instruction easiest to carry out is the one the thing
	 * is weighed in.
	 *
	 * <p>The cost of the other choice, measured rather than argued: with rice at 10 Kg and cardamom
	 * at 0.008 Kg, one unit for the column prints the cardamom as "0.008 Kg". Three leading zeroes
	 * on a spice that a cook reads as eight grams, in exchange for an alignment nobody is reading
	 * down. The rendered comparison is in {@code docs/work/proof/T-364.md}.
	 */
	@Test
	@DisplayName("an ingredient column keeps each line in its own unit: 10 Kg of rice beside 8 gm of cardamom")
	void anIngredientColumnKeepsEachLineInItsOwnUnit() {
		UUID cardamom = insertIngredient("Cardamom");
		insertLine(cardamom, "0.008", "KG", 3);

		TenantContext.set(temple);
		try {
			String card = RecipeCardTemplate.render(generationService.buildModel(recipe, null, "en"));

			assertThat(card)
					.contains("<td>Rice</td><td class=\"amt\">2 Kg</td>")
					.contains("<td>Cardamom</td><td class=\"amt\">8 gm</td>")
					.doesNotContain("0.008 Kg");
		} finally {
			TenantContext.clear();
		}
	}

	/**
	 * F6 (T-279): a figure of a lakh or more on the PO sheet is grouped the Indian way. The sheet's
	 * rupees were already right (T-268 grouped them by hand); its quantities were not, because
	 * {@code Quantities} used the JDK's en-IN formatter, which groups in threes — so a bulk order of
	 * 1,50,000 leaf plates went to the vendor as "150,000 pieces". This is the text the PDF is printed
	 * from: the print view and the PDF worker share {@code buildSheetModel}.
	 */
	@Test
	@DisplayName("the PO sheet writes lakhs the Indian way: 1,50,000 pieces, 2,50,000 Kg, a total of ₹1,03,00,000 (T-279)")
	void thePurchaseOrderSheetGroupsLakhs() {
		UUID adminId = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class);
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, address, gstin, phone)
				VALUES (?, 'Govind Wholesale', '12 Market Rd, Bengaluru', '29ABCDE1234F1Z5', '+919812345678')
				RETURNING id
				""", UUID.class, temple);
		UUID plates = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Leaf plates', 'Test', 'PIECES') RETURNING id
				""", UUID.class, temple);
		UUID rice = admin.queryForObject(
				"SELECT id FROM ingredients WHERE tenant_id = ? AND name = 'Rice'", UUID.class, temple);
		UUID po = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0279', ?, 'SENT', ?) RETURNING id
				""", UUID.class, temple, vendor, adminId);
		// 1,50,000 plates at ₹2 is ₹3,00,000; 2,50,000 Kg of rice at ₹40 is ₹1,00,00,000. The sheet
		// prints a rate per line and one total, so the total, ₹1,03,00,000, is the rupee figure here.
		poLine(po, plates, "150000", "PIECES", "2", 0);
		poLine(po, rice, "250000", "KG", "40", 1);

		TenantContext.set(temple);
		String html;
		try {
			html = generationService.renderPurchaseOrderHtml(po, "en");
		} finally {
			TenantContext.clear();
		}

		assertThat(html)
				.contains("1,50,000 pieces")
				.contains("2,50,000 Kg")
				.contains("₹2 / piece")
				.contains("₹40 / Kg")
				.contains("₹1,03,00,000")
				// The JDK's grouping, in threes, is nowhere on the sheet.
				.doesNotContain("150,000")
				.doesNotContain("250,000")
				.doesNotContain("10,300,000");
	}

	// ---------------------------------------------------------------------

	private void poLine(UUID po, UUID ingredient, String quantity, String unit, String price, int order) {
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price, line_order)
				VALUES (?, ?, ?, CAST(? AS numeric), ?, CAST(? AS numeric), ?)
				""", temple, po, ingredient, quantity, unit, price, order);
	}

	private void generateWithin(UUID doc) {
		TenantContext.set(temple);
		try {
			generationService.generate(doc);
		} finally {
			TenantContext.clear();
		}
	}

	private UUID insertPendingDocument(java.math.BigDecimal targetYield) {
		return admin.queryForObject("""
				INSERT INTO documents (tenant_id, kind, recipe_id, language, target_yield, status)
				VALUES (?, 'RECIPE_PDF', ?, 'en', ?, 'PENDING') RETURNING id
				""", UUID.class, temple, recipe, targetYield);
	}

	private UUID insertIngredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', 'KG') RETURNING id
				""", UUID.class, temple, name);
	}

	private void insertLine(UUID ingredient, String qty, String unit, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, ?)
				""", temple, recipe, ingredient, qty, unit, order);
	}

}
