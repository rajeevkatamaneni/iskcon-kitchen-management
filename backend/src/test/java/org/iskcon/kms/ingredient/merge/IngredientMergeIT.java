package org.iskcon.kms.ingredient.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-DUP-3, the merge of duplicate ingredients, through the full stack with row-level security in
 * play: the requests go through MockMvc as a signed-in Temple Admin, so the application runs as the
 * unprivileged role on a tenant-scoped connection exactly as in production. Fixtures and assertions
 * about rows are made as the superuser, which sees every temple.
 */
class IngredientMergeIT extends AbstractIntegrationTest {

	private static final String TEMPLE_A = "merge-temple-a";
	private static final String TEMPLE_B = "merge-temple-b";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;
	private UUID templeA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		MergeFixtures.purge(admin, TEMPLE_A);
		MergeFixtures.purge(admin, TEMPLE_B);
		templeA = MergeFixtures.temple(admin, TEMPLE_A, MergeFixtures.ADMIN_UID, MergeFixtures.MANAGER_UID);
		stubVerifier.accept(MergeFixtures.ADMIN_UID);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DROP TRIGGER IF EXISTS merge_it_fail_late ON ingredients");
		admin.execute("DROP FUNCTION IF EXISTS merge_it_fail_late()");
		MergeFixtures.purge(admin, TEMPLE_A);
		MergeFixtures.purge(admin, TEMPLE_B);
	}

	// =====================================================================
	// The acceptance criteria, on the curd group
	// =====================================================================

	@Test
	@DisplayName("AC: merging the curd group — stock summed, recipes noted, one shopping line, no reference left, audited, findable")
	void theCurdGroup() throws Exception {
		MergeFixtures.curdGroup(admin, templeA);
		UUID curd = id("Curd");
		UUID fresh = id("Curd, fresh");
		UUID sour = id("Curd, sour");
		UUID whisked = id("Curd, whisked");
		List<UUID> merged = List.of(fresh, sour, whisked);
		UUID nandini = MergeFixtures.vendor(admin, templeA, "Nandini");

		BigDecimal onHandBefore = onHandBase(List.of(curd, fresh, sour, whisked));
		assertThat(onHandBefore).isEqualByComparingTo("14000");
		assertThat(curdLinesOnTheShoppingList()).as("before: one line per duplicate").hasSize(2);

		String body = mvc.perform(merge(curd, merged, Map.of(nandini, curd)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.keptIngredientId").value(curd.toString()))
				.andExpect(jsonPath("$.mergedIngredientIds.length()").value(3))
				.andExpect(jsonPath("$.repointed.stock_movements").value(4))
				.andExpect(jsonPath("$.repointed.recipe_ingredients").value(3))
				.andReturn().getResponse().getContentAsString();
		JsonNode result = json.readTree(body);
		assertThat(result.get("aliasesAdded").toString())
				.isEqualTo("[\"Curd, fresh\",\"Curd, sour\",\"Huli mosaru\",\"Curd, whisked\"]");

		// On-hand stock for Curd equals the sum before the merge, and the history is kept: every
		// movement is still there with the quantity and unit it was recorded in.
		assertThat(onHandBase(List.of(curd))).isEqualByComparingTo(onHandBefore);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ?", Integer.class, curd)).isEqualTo(5);
		assertThat(admin.queryForObject("""
				SELECT quantity || ' ' || unit FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'PO_RECEIPT'
				""", String.class, curd)).isEqualTo("1500.000 GM");

		// Every recipe that used "Curd, sour" now uses Curd with the note "sour"; a line that had a
		// note keeps it after the moved one; a recipe using both keeps two lines.
		assertThat(recipeLines("Kadhi")).containsExactly("Curd|sour|500.000 GM", "Curd|-|1.000 KG");
		assertThat(recipeLines("Raita")).containsExactly("Curd|whisked, chilled|1.000 KG");
		assertThat(recipeLines("Mosaru anna")).containsExactly("Curd|fresh|2.000 KG");
		assertThat(admin.queryForList(
				"SELECT version FROM recipes WHERE tenant_id = ? ORDER BY name", Integer.class, templeA))
				.as("each changed recipe's version moves, so its cached translations go")
				.containsExactly(2, 2, 2);

		// The shopping list shows one Curd line, through the real endpoint; the two hand-added
		// quantities are one decision in Kg (3,000 gm + 1 Kg).
		assertThat(curdLinesOnTheShoppingList()).containsExactly("Curd");
		assertThat(admin.queryForObject(
				"SELECT suggested_qty || ' ' || unit FROM shopping_list_lines WHERE ingredient_id = ?",
				String.class, curd)).isEqualTo("4.000 KG");

		// No reference to a merged-away ingredient remains, in any table the catalogue knows.
		assertThat(referencesTo(merged)).as("rows still naming a merged-away ingredient").isEmpty();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE id = ANY (?::uuid[])", Integer.class, array(merged)))
				.isZero();

		// Supplies: Nandini keeps Curd's price (the choice) and stays preferred, and the lead time it
		// gave for "Curd, sour" comes across because Curd's row had none; Akshaya's two equal prices
		// fold into one row, with the lead time it had given.
		assertThat(admin.queryForList("""
				SELECT v.name || '|' || s.last_price || '|' || s.preferred || '|' || coalesce(s.lead_time_days::text, '-')
				FROM vendor_supplies s JOIN vendors v ON v.id = s.vendor_id
				WHERE s.ingredient_id = ? ORDER BY v.name
				""", String.class, curd))
				.containsExactly("Akshaya|58.0000|false|1", "Nandini|60.0000|true|2");

		// Packs: "Tub = 5,000 gm" is the same size as Curd's "Tub = 5 Kg" and collapses into it, the
		// order line following; the Pouch moves across as it was entered.
		assertThat(admin.queryForList(
				"SELECT name || ' ' || quantity || ' ' || unit FROM ingredient_pack_sizes WHERE ingredient_id = ? ORDER BY base_quantity",
				String.class, curd)).containsExactly("Pouch 500.000 GM", "Tub 5.000 KG");
		assertThat(admin.queryForObject("""
				SELECT p.quantity || ' ' || p.unit FROM purchase_order_lines l
				JOIN ingredient_pack_sizes p ON p.id = l.pack_size_id WHERE l.ingredient_id = ?
				""", String.class, curd)).isEqualTo("5.000 KG");

		// Price history and market rate, converted to ₹ per Kg: ₹0.065/gm is ₹65/Kg.
		assertThat(admin.queryForList(
				"SELECT price_per_unit::text FROM vendor_price_history WHERE ingredient_id = ? ORDER BY price_per_unit, effective_on",
				String.class, curd)).containsExactly("60.0000", "65.0000", "65.0000");
		assertThat(admin.queryForList(
				"SELECT rate::text FROM ingredient_market_rate_history WHERE ingredient_id = ? ORDER BY rate",
				String.class, curd)).containsExactly("62.0000", "70.0000");
		assertThat(admin.queryForObject(
				"SELECT market_rate || ' ' || market_rate_on FROM ingredients WHERE id = ?", String.class, curd))
				.as("the newest market rate, in the kept unit").isEqualTo("70.0000 2026-09-15");

		// Inventory: one item, Curd's own place and level, the blank notes filled from "Curd, sour".
		assertThat(admin.queryForList("""
				SELECT storage_location || '|' || reorder_threshold || '|' || notes
				FROM inventory_items WHERE tenant_id = ?
				""", String.class, templeA)).containsExactly("Cold room|5.000|Keep covered");

		// The audit shows the merge, its after-state read back from the rows.
		Map<String, Object> event = admin.queryForMap("""
				SELECT reason, after_state->'kept'->>'name' AS kept,
					   after_state->'kept'->>'onHand' AS on_hand,
					   (SELECT sum(value::bigint) FROM jsonb_each_text(after_state->'stillPointingAtMerged')) AS left_behind,
					   jsonb_array_length(before_state->'merged') AS merged
				FROM audit_events WHERE action = 'INGREDIENT_MERGED' AND entity_id = ?
				""", curd);
		assertThat(event.get("reason")).isEqualTo("Merged Curd, fresh, Curd, sour, Curd, whisked into Curd.");
		assertThat(event.get("kept")).isEqualTo("Curd");
		assertThat(new BigDecimal((String) event.get("on_hand"))).isEqualByComparingTo("14");
		assertThat(((Number) event.get("left_behind")).longValue()).isZero();
		assertThat(event.get("merged")).isEqualTo(3);
		assertThat(admin.queryForList(
				"SELECT reason FROM audit_events WHERE action = 'INGREDIENT_DELETED' AND tenant_id = ?",
				String.class, templeA)).containsExactly("Merged into Curd.", "Merged into Curd.", "Merged into Curd.");

		// Searching for "Curd, sour" finds Curd; creating it again is stopped as a lookalike of Curd.
		mvc.perform(get("/api/v1/ingredients/search").param("q", "Curd, sour").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(curd.toString()));
		mvc.perform(get("/api/v1/ingredients/search").param("q", "huli").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN))
				.andExpect(jsonPath("$[0].id").value(curd.toString()));
		mvc.perform(post("/api/v1/ingredients").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Curd, sour\",\"category\":\"Dairy\",\"unit\":\"KG\",\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='existingIngredientName')].message").value("Curd"));

		// The merged-away names are Curd's aliases in both places they live.
		assertThat(admin.queryForObject("SELECT array_to_string(aliases, '|') FROM ingredients WHERE id = ?",
				String.class, curd)).isEqualTo("Dahi|Curd, fresh|Curd, sour|Huli mosaru|Curd, whisked");
		assertThat(admin.queryForList(
				"SELECT normalised_alias || '=' || alias FROM ingredient_aliases WHERE ingredient_id = ? ORDER BY 1",
				String.class, curd)).containsExactly("curd=Curd, fresh", "dahi=Dahi", "huli mosaru=Huli mosaru");
	}

	@Test
	@DisplayName("proposals: the curd group with each note, Curd kept; nothing proposed for an ingredient with no duplicate")
	void proposals() throws Exception {
		MergeFixtures.curdGroup(admin, templeA);

		String body = mvc.perform(get("/api/v1/ingredients/merge-proposals").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode proposals = json.readTree(body);

		assertThat(proposals).hasSize(1);
		JsonNode curd = proposals.get(0);
		assertThat(curd.get("keep").get("name").asText()).isEqualTo("Curd");
		assertThat(curd.get("keep").get("preparationNote").isNull()).isTrue();
		assertThat(curd.get("keep").get("recipeLineCount").asInt()).isEqualTo(1);
		assertThat(curd.get("keep").get("supplyCount").asInt()).isEqualTo(1);
		List<String> merge = new ArrayList<>();
		curd.get("merge").forEach(m -> merge.add(m.get("name").asText() + "|" + m.get("preparationNote").asText()
				+ "|" + m.get("unit").asText() + "|" + m.get("onHand").decimalValue().stripTrailingZeros().toPlainString()));
		assertThat(merge).containsExactly("Curd, fresh|fresh|KG|1.5", "Curd, sour|sour|GM|1500", "Curd, whisked|whisked|KG|1");
	}

	@Test
	@DisplayName("preview: stock after in the kept unit, the price conflict with both prices, no unit problem")
	void preview() throws Exception {
		MergeFixtures.curdGroup(admin, templeA);
		UUID curd = id("Curd");

		mvc.perform(post("/api/v1/ingredients/merges/preview").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(groupJson(curd, List.of(id("Curd, fresh"), id("Curd, sour"), id("Curd, whisked")), Map.of())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.onHandAfter").value(14))
				.andExpect(jsonPath("$.unitProblem").doesNotExist())
				.andExpect(jsonPath("$.merge[1].preparationNote").value("sour"))
				.andExpect(jsonPath("$.conflicts.length()").value(1))
				.andExpect(jsonPath("$.conflicts[0].vendorName").value("Nandini"))
				.andExpect(jsonPath("$.conflicts[0].prices[?(@.ingredientName=='Curd, sour')].listPrice").value(0.065))
				.andExpect(jsonPath("$.conflicts[0].prices[?(@.ingredientName=='Curd, sour')].unit").value("GM"))
				.andExpect(jsonPath("$.conflicts[0].prices[?(@.ingredientName=='Curd, sour')].packLabel").value("Tub = 5 Kg"))
				.andExpect(jsonPath("$.conflicts[0].prices[?(@.ingredientName=='Curd')].listPrice").value(60.0));
		// A preview writes nothing.
		assertThat(admin.queryForObject("SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, templeA))
				.isEqualTo(5);
	}

	// =====================================================================
	// Units
	// =====================================================================

	@Test
	@DisplayName("Kg + gm: stock, list price, price history, market rate, reorder level and pack sizes all come out in Kg")
	void kilogramsAndGrams() throws Exception {
		UUID sugar = ingredient("Sugar", "KG");
		UUID sugarGm = ingredient("Sugar (loose)", "GM");
		UUID v1 = vendorRow("Mysore Sugars");
		UUID actor = adminUser();
		admin.update("INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id) VALUES (?, ?, gen_random_uuid(), 1, 'KG', 'ADJUSTMENT', ?), (?, ?, gen_random_uuid(), 2500, 'GM', 'ADJUSTMENT', ?)",
				templeA, sugar, actor, templeA, sugarGm, actor);
		admin.update("INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price) VALUES (?, ?, ?, 0.044)", templeA, v1, sugarGm);
		admin.update("INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit, effective_on, source, set_by) VALUES (?, ?, ?, 0.044, DATE '2026-09-01', 'MANUAL', ?)",
				templeA, v1, sugarGm, actor);
		admin.update("INSERT INTO ingredient_market_rate_history (tenant_id, ingredient_id, rate, effective_on, source, set_by) VALUES (?, ?, 0.046, DATE '2026-09-12', 'MANUAL', ?)",
				templeA, sugarGm, actor);
		admin.update("UPDATE ingredients SET market_rate = 0.046, market_rate_on = DATE '2026-09-12', market_rate_source = 'MANUAL' WHERE id = ?", sugarGm);
		admin.update("INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold) VALUES (?, ?, 1500)", templeA, sugarGm);
		admin.update("INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, quantity, unit) VALUES (?, ?, 1000, 'GM')", templeA, sugarGm);

		mvc.perform(merge(sugar, List.of(sugarGm), Map.of())).andExpect(status().isOk());

		assertThat(onHandBase(List.of(sugar))).isEqualByComparingTo("3500");
		assertThat(admin.queryForList("SELECT quantity || ' ' || unit FROM stock_movements WHERE ingredient_id = ? ORDER BY unit",
				String.class, sugar)).as("each movement as it was recorded").containsExactly("2500.000 GM", "1.000 KG");
		assertThat(admin.queryForObject("SELECT last_price::text FROM vendor_supplies WHERE ingredient_id = ?", String.class, sugar))
				.isEqualTo("44.0000");
		assertThat(admin.queryForObject("SELECT price_per_unit::text FROM vendor_price_history WHERE ingredient_id = ?", String.class, sugar))
				.isEqualTo("44.0000");
		assertThat(admin.queryForObject("SELECT rate::text FROM ingredient_market_rate_history WHERE ingredient_id = ?", String.class, sugar))
				.isEqualTo("46.0000");
		assertThat(admin.queryForObject("SELECT market_rate::text FROM ingredients WHERE id = ?", String.class, sugar))
				.isEqualTo("46.0000");
		assertThat(admin.queryForObject("SELECT reorder_threshold::text FROM inventory_items WHERE ingredient_id = ?", String.class, sugar))
				.isEqualTo("1.500");
		assertThat(admin.queryForObject("SELECT quantity || ' ' || unit FROM ingredient_pack_sizes WHERE ingredient_id = ?", String.class, sugar))
				.isEqualTo("1000.000 GM");
	}

	@Test
	@DisplayName("Kg + pieces: refused with KMS-400171 naming both ingredients and units; the preview says the same; nothing changes")
	void kilogramsAndPiecesAreRefused() throws Exception {
		UUID coconut = ingredient("Coconut", "PIECES");
		UUID grated = ingredient("Coconut, grated", "KG");
		String before = everythingAbout(List.of(coconut, grated));

		mvc.perform(merge(coconut, List.of(grated), Map.of()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400171"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='keptIngredient')].message").value("Coconut"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='keptUnit')].message").value("pieces"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='otherIngredient')].message").value("Coconut, grated"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='otherUnit')].message").value("Kg"));

		mvc.perform(post("/api/v1/ingredients/merges/preview").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(groupJson(coconut, List.of(grated), Map.of())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unitProblem").value("Coconut is in pieces, Coconut, grated is in Kg"));

		assertThat(everythingAbout(List.of(coconut, grated))).isEqualTo(before);
	}

	// =====================================================================
	// Price conflicts
	// =====================================================================

	@Test
	@DisplayName("one vendor, two prices: refused with KMS-400172 naming the vendor, then merged with the chosen price")
	void priceConflictThenChoice() throws Exception {
		UUID cashew = ingredient("Cashew", "KG");
		UUID halved = ingredient("Cashew, halved", "KG");
		UUID vendor = vendorRow("Kalasipalya");
		admin.update("INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price) VALUES (?, ?, ?, 800), (?, ?, ?, 780)",
				templeA, vendor, cashew, templeA, vendor, halved);
		String before = everythingAbout(List.of(cashew, halved));

		mvc.perform(merge(cashew, List.of(halved), Map.of()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400172"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='vendorName')].message").value("Kalasipalya"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='vendorId')].message").value(vendor.toString()));
		assertThat(everythingAbout(List.of(cashew, halved))).as("the refusal changed nothing").isEqualTo(before);

		// A choice naming an ingredient outside the conflict is not a choice this group can take.
		mvc.perform(merge(cashew, List.of(halved), Map.of(vendor, UUID.randomUUID())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400173"));

		mvc.perform(merge(cashew, List.of(halved), Map.of(vendor, halved))).andExpect(status().isOk());
		assertThat(admin.queryForList("SELECT last_price::text FROM vendor_supplies WHERE tenant_id = ?", String.class, templeA))
				.containsExactly("780.0000");
		assertThat(admin.queryForObject("SELECT ingredient_id FROM vendor_supplies WHERE tenant_id = ?", UUID.class, templeA))
				.isEqualTo(cashew);
	}

	// =====================================================================
	// One transaction per group
	// =====================================================================

	@Test
	@DisplayName("a failure at the last step rolls the whole group back: every table as it was")
	void aFailureMidGroupRollsTheGroupBack() throws Exception {
		MergeFixtures.curdGroup(admin, templeA);
		UUID curd = id("Curd");
		List<UUID> group = List.of(curd, id("Curd, fresh"), id("Curd, sour"), id("Curd, whisked"));
		String before = everythingAbout(group);

		// Refuses the very last write the merge makes — removing the merged-away ingredients — so every
		// step before it has already run when it fails. Scoped to this test's temple.
		admin.execute("""
				CREATE FUNCTION merge_it_fail_late() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  IF OLD.name = 'Curd, whisked' THEN RAISE EXCEPTION 'merge_it_fail_late'; END IF;
				  RETURN OLD;
				END $$""");
		admin.execute("CREATE TRIGGER merge_it_fail_late BEFORE DELETE ON ingredients FOR EACH ROW EXECUTE FUNCTION merge_it_fail_late()");

		mvc.perform(merge(curd, group.subList(1, 4), Map.of(MergeFixtures.vendor(admin, templeA, "Nandini"), curd)))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("KMS-500001"));

		assertThat(everythingAbout(group)).isEqualTo(before);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE tenant_id = ? AND action = 'INGREDIENT_MERGED'", Integer.class, templeA))
				.isZero();
	}

	// =====================================================================
	// Who, and which temple
	// =====================================================================

	@Test
	@DisplayName("a Kitchen Manager is refused all three endpoints (403), and nothing changes")
	void aKitchenManagerIsRefused() throws Exception {
		MergeFixtures.curdGroup(admin, templeA);
		UUID curd = id("Curd");
		List<UUID> merged = List.of(id("Curd, fresh"));
		stubVerifier.accept(MergeFixtures.MANAGER_UID);

		mvc.perform(get("/api/v1/ingredients/merge-proposals").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/ingredients/merges/preview").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(groupJson(curd, merged, Map.of())))
				.andExpect(status().isForbidden());
		mvc.perform(merge(curd, merged, Map.of())).andExpect(status().isForbidden());

		assertThat(admin.queryForObject("SELECT count(*) FROM ingredients WHERE id = ?", Integer.class, merged.get(0)))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("another temple's ingredient cannot be merged, previewed or proposed: not found, KMS-400173")
	void anotherTemplesIngredientCannotBeMerged() throws Exception {
		UUID curd = ingredient("Curd", "KG");
		UUID templeB = MergeFixtures.temple(admin, TEMPLE_B, "uid-merge-admin-b", null);
		UUID theirs = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Curd, sour', 'Dairy', 'KG') RETURNING id
				""", UUID.class, templeB);

		mvc.perform(merge(curd, List.of(theirs), Map.of()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400173"));
		mvc.perform(post("/api/v1/ingredients/merges/preview").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(groupJson(curd, List.of(theirs), Map.of())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400173"));
		// Nor the other way: temple B's Curd, sour is no duplicate of temple A's Curd.
		mvc.perform(get("/api/v1/ingredients/merge-proposals").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN))
				.andExpect(jsonPath("$.length()").value(0));

		assertThat(admin.queryForObject("SELECT tenant_id FROM ingredients WHERE id = ?", UUID.class, theirs))
				.isEqualTo(templeB);
	}

	@Test
	@DisplayName("an invalid group is KMS-400173: kept one also merged, an id twice, nothing to merge, an unknown id")
	void invalidGroups() throws Exception {
		UUID curd = ingredient("Curd", "KG");
		UUID sour = ingredient("Curd, sour", "KG");

		for (String body : List.of(
				groupJson(curd, List.of(curd), Map.of()),
				groupJson(curd, List.of(sour, sour), Map.of()),
				groupJson(curd, List.of(), Map.of()),
				groupJson(curd, List.of(UUID.randomUUID()), Map.of()),
				"{\"merge\":[{\"ingredientId\":\"" + sour + "\"}]}")) {
			mvc.perform(post("/api/v1/ingredients/merges").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400173"));
		}
		assertThat(admin.queryForObject("SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, templeA))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("a note typed by the admin wins over the proposed one, and a blank one means no note")
	void theAdminsNoteWins() throws Exception {
		UUID curd = ingredient("Curd", "KG");
		UUID sour = ingredient("Curd, sour", "KG");
		UUID thick = ingredient("Curd, thick", "KG");
		UUID recipe = recipeUsing(sour, thick);

		mvc.perform(post("/api/v1/ingredients/merges").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"keepIngredientId\":\"" + curd + "\",\"merge\":["
						+ "{\"ingredientId\":\"" + sour + "\",\"preparationNote\":\"soured overnight\"},"
						+ "{\"ingredientId\":\"" + thick + "\",\"preparationNote\":\"  \"}]}"))
				.andExpect(status().isOk());

		assertThat(admin.queryForList(
				"SELECT coalesce(preparation_note, '-') FROM recipe_ingredients WHERE recipe_id = ? ORDER BY line_order",
				String.class, recipe)).containsExactly("soured overnight", "-");
	}

	// =====================================================================

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder merge(
			UUID keep, List<UUID> merge, Map<UUID, UUID> choices) {
		return post("/api/v1/ingredients/merges").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(groupJson(keep, merge, choices));
	}

	private static String groupJson(UUID keep, List<UUID> merge, Map<UUID, UUID> choices) {
		StringBuilder s = new StringBuilder("{\"keepIngredientId\":\"" + keep + "\",\"merge\":[");
		for (int i = 0; i < merge.size(); i++) {
			s.append(i == 0 ? "" : ",").append("{\"ingredientId\":\"").append(merge.get(i)).append("\"}");
		}
		s.append("],\"supplyPriceChoices\":[");
		int i = 0;
		for (Map.Entry<UUID, UUID> c : choices.entrySet()) {
			s.append(i++ == 0 ? "" : ",").append("{\"vendorId\":\"").append(c.getKey())
					.append("\",\"keepPriceFromIngredientId\":\"").append(c.getValue()).append("\"}");
		}
		return s.append("]}").toString();
	}

	private UUID id(String name) {
		return MergeFixtures.ingredient(admin, templeA, name);
	}

	private UUID ingredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Pantry', ?) RETURNING id
				""", UUID.class, templeA, name, unit);
	}

	private UUID vendorRow(String name) {
		return admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919876500499') RETURNING id",
				UUID.class, templeA, name);
	}

	private UUID adminUser() {
		return admin.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", UUID.class, MergeFixtures.ADMIN_UID);
	}

	private UUID recipeUsing(UUID first, UUID second) {
		UUID cat = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Sides') RETURNING id", UUID.class, templeA);
		UUID recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Raita', ?, 5, 'KG') RETURNING id
				""", UUID.class, templeA, cat);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 1, 'KG', 1), (?, ?, ?, 1, 'KG', 2)
				""", templeA, recipe, first, templeA, recipe, second);
		return recipe;
	}

	private BigDecimal onHandBase(List<UUID> ids) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE ingredient_id = ANY (?::uuid[])
				""", BigDecimal.class, array(ids));
	}

	private List<String> recipeLines(String recipe) {
		return admin.queryForList("""
				SELECT i.name || '|' || coalesce(ri.preparation_note, '-') || '|' || ri.quantity || ' ' || ri.unit
				FROM recipe_ingredients ri
				JOIN recipes r ON r.id = ri.recipe_id
				JOIN ingredients i ON i.id = ri.ingredient_id
				WHERE r.tenant_id = ? AND r.name = ?
				ORDER BY ri.line_order
				""", String.class, templeA, recipe);
	}

	private List<String> curdLinesOnTheShoppingList() throws Exception {
		String body = mvc.perform(get("/api/v1/shopping-list").header("Authorization", "Bearer " + StubTokenVerifier.TOKEN))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> names = new ArrayList<>();
		json.readTree(body).forEach(line -> {
			String name = line.get("ingredientName").asText();
			if (name.startsWith("Curd")) {
				names.add(name);
			}
		});
		return names;
	}

	/**
	 * Every row in every table the catalogue says points at an ingredient, that points at one of
	 * {@code ids} — as "table: row". Read from the catalogue at the time of asking, so a table added
	 * later is covered without this test changing.
	 */
	private List<String> referencesTo(List<UUID> ids) {
		List<String> found = new ArrayList<>();
		for (Map<String, Object> fk : catalogue()) {
			String table = (String) fk.get("table_name");
			String column = (String) fk.get("column_name");
			found.addAll(admin.queryForList(
					"SELECT '" + table + ": ' || to_jsonb(t)::text FROM " + table + " t WHERE " + column + " = ANY (?::uuid[])",
					String.class, array(ids)));
		}
		return found;
	}

	/**
	 * A fingerprint of everything the merge could touch for these ingredients: the ingredient rows,
	 * every row in every referencing table, the recipes' versions, and the temple's audit trail.
	 * Equal before and after means nothing changed.
	 */
	private String everythingAbout(List<UUID> ids) {
		List<String> parts = new ArrayList<>();
		parts.addAll(admin.queryForList(
				"SELECT to_jsonb(i)::text FROM ingredients i WHERE id = ANY (?::uuid[]) ORDER BY id", String.class, array(ids)));
		for (Map<String, Object> fk : catalogue()) {
			parts.addAll(admin.queryForList(
					"SELECT to_jsonb(t)::text FROM " + fk.get("table_name") + " t WHERE " + fk.get("column_name")
							+ " = ANY (?::uuid[]) ORDER BY 1", String.class, array(ids)));
		}
		parts.addAll(admin.queryForList(
				"SELECT to_jsonb(r)::text FROM recipes r WHERE tenant_id = ? ORDER BY id", String.class, templeA));
		parts.addAll(admin.queryForList(
				"SELECT to_jsonb(p)::text FROM ingredient_pack_sizes p WHERE tenant_id = ? ORDER BY id", String.class, templeA));
		parts.add(String.valueOf(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, templeA)));
		return String.join("\n", parts);
	}

	private List<Map<String, Object>> catalogue() {
		return admin.queryForList("""
				SELECT rel.relname AS table_name, att.attname AS column_name
				FROM pg_constraint con
				JOIN pg_class rel ON rel.oid = con.conrelid
				JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
				WHERE con.contype = 'f' AND con.confrelid = 'public.ingredients'::regclass
				ORDER BY 1, 2
				""");
	}

	private static String array(List<UUID> ids) {
		return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
	}
}
