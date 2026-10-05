package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.assistant.mcp.InventoryTools;
import org.iskcon.kms.assistant.mcp.InventoryTools.InventoryResult;
import org.iskcon.kms.assistant.mcp.InventoryTools.InventoryRow;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The glossary's one-off machine fill of stock names (E10-T8c), over HTTP as a signed-in person,
 * with the suite's deterministic stub translator ({@code "[kn] <text>"}), so what the machine was
 * handed is visible in what it wrote.
 *
 * <p>Two temples, each with stock and one name a person already typed. Every read and write goes
 * through the application's unprivileged connection, so it is RLS, not a {@code WHERE}, that keeps
 * temple A's fill out of temple B. The assertions are made through the privileged connection, which
 * sees both temples, so a leak would show rather than hide.
 */
@AutoConfigureMockMvc
class StockNameFillIT extends AbstractIntegrationTest {

	private static final String FILL = "/api/v1/translation-glossary/fill-stock-names";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private InventoryTools inventoryTools;

	private JdbcTemplate admin;
	private int phones;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin = new JdbcTemplate(adminDataSource());
		templeA = tenant("fill-a", "Fill Temple A");
		templeB = tenant("fill-b", "Fill Temple B");
		user(templeA, "uid-fill-a", "TEMPLE_ADMIN");
		user(templeB, "uid-fill-b", "TEMPLE_ADMIN");
		user(templeA, "uid-fill-volunteer", "VOLUNTEER");

		// Temple A: three stock items, one of them filed catalogue-style, and a person's name for
		// Ghee. A fourth ingredient is in the recipe catalogue but not in the store.
		stock(templeA, "Ghee");
		stock(templeA, "Rice, broken");
		stock(templeA, "Mace");
		ingredient(templeA, "Saffron");
		glossary(templeA, "ghee", "ತುಪ್ಪ");

		// Temple B: its own Mace, unnamed, and its own name for Rice, broken. Neither may change A.
		stock(templeB, "Mace");
		stock(templeB, "Rice, broken");
		glossary(templeB, "Rice, broken", "ನುಚ್ಚಕ್ಕಿ");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("names only the stock items with no entry, read aloud to the machine, marked as the machine's")
	void fillsOnlyTheMissing() throws Exception {
		signIn("uid-fill-a");
		mvc.perform(authed(post(FILL)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.language").value("kn"))
				.andExpect(jsonPath("$.stockItems").value(3))
				.andExpect(jsonPath("$.added").value(2))
				.andExpect(jsonPath("$.provider").value("stub"));

		Map<String, Map<String, Object>> a = glossaryOf(templeA);
		assertThat(a).containsOnlyKeys("ghee", "Rice, broken", "Mace");

		// The person's entry, untouched and still unmarked.
		assertThat(a.get("ghee").get("target_term")).isEqualTo("ತುಪ್ಪ");
		assertThat(a.get("ghee").get("machine_provider")).isNull();
		assertThat(a.get("ghee").get("machine_translated_at")).isNull();

		// The machine heard "Broken rice", not "Rice, broken"; the row is keyed on the filed name,
		// which is what every reader looks it up by.
		assertThat(a.get("Rice, broken").get("target_term")).isEqualTo("[kn] Broken rice");
		assertThat(a.get("Mace").get("target_term")).isEqualTo("[kn] Mace");
		assertThat(a.get("Mace").get("machine_provider")).isEqualTo("stub");
		assertThat(a.get("Mace").get("machine_translated_at")).isNotNull();

		// Saffron is not stock, so it is not named.
		assertThat(a).doesNotContainKey("Saffron");
	}

	@Test
	@DisplayName("running it again adds nothing and changes nothing")
	void isIdempotent() throws Exception {
		signIn("uid-fill-a");
		mvc.perform(authed(post(FILL))).andExpect(jsonPath("$.added").value(2));
		Map<String, Map<String, Object>> first = glossaryOf(templeA);

		mvc.perform(authed(post(FILL)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stockItems").value(3))
				.andExpect(jsonPath("$.added").value(0));

		assertThat(glossaryOf(templeA)).isEqualTo(first);
	}

	@Test
	@DisplayName("never overwrites a machine name either, and a person's correction clears the mark")
	void neverOverwritesAndACorrectionIsAPersons() throws Exception {
		signIn("uid-fill-a");
		mvc.perform(authed(post(FILL))).andExpect(status().isOk());
		OffsetDateTime written = (OffsetDateTime) glossaryOf(templeA).get("Mace").get("machine_translated_at");

		// A new stock item arrives; the next fill names it and leaves Mace exactly as it was.
		stock(templeA, "Ajwain");
		mvc.perform(authed(post(FILL))).andExpect(jsonPath("$.added").value(1));
		assertThat(glossaryOf(templeA).get("Mace").get("machine_translated_at")).isEqualTo(written);
		assertThat(glossaryOf(templeA).get("Ajwain").get("target_term")).isEqualTo("[kn] Ajwain");

		// The Temple Admin corrects Mace on the glossary screen: it becomes theirs.
		mvc.perform(authed(post("/api/v1/translation-glossary"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"language\":\"kn\",\"sourceTerm\":\"Mace\",\"targetTerm\":\"ಜಾಪತ್ರೆ\"}"))
				.andExpect(status().isCreated());
		Map<String, Object> mace = glossaryOf(templeA).get("Mace");
		assertThat(mace.get("target_term")).isEqualTo("ಜಾಪತ್ರೆ");
		assertThat(mace.get("machine_provider")).isNull();
		assertThat(mace.get("machine_translated_at")).isNull();
	}

	@Test
	@DisplayName("one temple's fill never reads or writes another temple's glossary")
	void isTenantScoped() throws Exception {
		signIn("uid-fill-a");
		mvc.perform(authed(post(FILL))).andExpect(jsonPath("$.added").value(2));

		// B's own name for Rice, broken did not stop A's being filled, and B got nothing from A.
		Map<String, Map<String, Object>> b = glossaryOf(templeB);
		assertThat(b).containsOnlyKeys("Rice, broken");
		assertThat(b.get("Rice, broken").get("target_term")).isEqualTo("ನುಚ್ಚಕ್ಕಿ");

		// B's own fill names only B's Mace, and leaves B's typed name alone.
		signIn("uid-fill-b");
		mvc.perform(authed(post(FILL)))
				.andExpect(jsonPath("$.stockItems").value(2))
				.andExpect(jsonPath("$.added").value(1));
		b = glossaryOf(templeB);
		assertThat(b).containsOnlyKeys("Rice, broken", "Mace");
		assertThat(b.get("Rice, broken").get("target_term")).isEqualTo("ನುಚ್ಚಕ್ಕಿ");
		assertThat(glossaryOf(templeA)).hasSize(3);
	}

	@Test
	@DisplayName("refused to a role without the glossary's permission, and nothing is written")
	void permissionIsEnforced() throws Exception {
		signIn("uid-fill-volunteer");
		mvc.perform(authed(post(FILL))).andExpect(status().isForbidden());

		assertThat(glossaryOf(templeA)).containsOnlyKeys("ghee");
	}

	@Test
	@DisplayName("get_inventory and list_low_stock hand the model the machine names as name_kn")
	void toolsReturnMachineNames() throws Exception {
		signIn("uid-fill-a");
		mvc.perform(authed(post(FILL))).andExpect(status().isOk());

		TenantContext.set(templeA);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				"assistant", null, AuthorityUtils.createAuthorityList("MANAGE_INVENTORY")));

		InventoryResult all = inventoryTools.getInventory(null);
		assertThat(all.items()).extracting(InventoryRow::name, InventoryRow::nameKn).containsExactly(
				org.assertj.core.groups.Tuple.tuple("Ghee", "ತುಪ್ಪ"),
				org.assertj.core.groups.Tuple.tuple("Mace", "[kn] Mace"),
				org.assertj.core.groups.Tuple.tuple("Rice, broken", "[kn] Broken rice"));

		// Every item is stocked at nothing against a level of 1, so every one is low.
		InventoryResult low = inventoryTools.listLowStock();
		assertThat(low.items()).extracting(InventoryRow::nameKn)
				.containsExactly("ತುಪ್ಪ", "[kn] Mace", "[kn] Broken rice");

		// And the tools wrote nothing: still exactly the three rows the fill left.
		assertThat(glossaryOf(templeA)).hasSize(3);
	}

	// ---------------------------------------------------------------------

	private Map<String, Map<String, Object>> glossaryOf(UUID tenant) {
		List<Map<String, Object>> rows = admin.queryForList("""
				SELECT source_term, target_term, machine_provider, machine_translated_at
				FROM translation_glossary WHERE tenant_id = ? AND language = 'kn'
				""", tenant);
		Map<String, Map<String, Object>> out = new java.util.HashMap<>();
		for (Map<String, Object> row : rows) {
			Object at = row.get("machine_translated_at");
			if (at instanceof java.sql.Timestamp ts) {
				row.put("machine_translated_at", ts.toInstant().atOffset(java.time.ZoneOffset.UTC));
			}
			out.put((String) row.get("source_term"), row);
		}
		return out;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer " + StubTokenVerifier.TOKEN);
	}

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void user(UUID tenant, String uid, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test User', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, uid + "@example.com", "+9198765%05d".formatted(++phones), role);
	}

	private UUID ingredient(UUID tenant, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void stock(UUID tenant, String name) {
		UUID ingredient = ingredient(tenant, name);
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, 1)
				""", tenant, ingredient);
	}

	private void glossary(UUID tenant, String source, String target) {
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (?, 'kn', ?, ?)
				""", tenant, source, target);
	}
}
