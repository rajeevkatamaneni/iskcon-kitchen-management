package org.iskcon.kms.shoppinglist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Adding a line to the shopping list by hand (T-027).
 *
 * <p><strong>The defining test is {@link #handAddedLineSurvivesARealRegeneration()}, and it is
 * written against a real regeneration rather than against the {@code edited} column.</strong> The
 * column is only the mechanism; the fact that has to hold is that the line is still on the list
 * after the job that runs at 04:30 has been through it. Asserting {@code edited == true} would pass
 * just as happily if the delete clause were later changed to ignore the column, and the failure it
 * would miss is silent — a line the cook typed, gone by morning, with nobody watching.
 *
 * <p>The fixture is built so that nothing here can be a regeneration artefact. Rice has an
 * inventory row below its threshold, so the regenerator suggests it and the run does real work.
 * The gas cylinder and the jaggery have no inventory row, no recipe and no meal plan, so no demand
 * stream can ever reach them: if they are on the list, a person put them there.
 */
@AutoConfigureMockMvc
@Import(HandAddedLineIT.StubVerifierConfiguration.class)
class HandAddedLineIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;

	/** Food. Below its reorder threshold, so regeneration suggests it of its own accord. */
	private UUID rice;

	/**
	 * A supply, not food — T-023's flag (D-1). LPG is bought, received, stored, used up and wanted
	 * back when it runs low exactly as an ingredient is, which is why it lives in this catalogue
	 * instead of a table of its own. Nothing in the demand streams can suggest it: it is in no
	 * recipe, so there is no shortfall, and the temple keeps no inventory row for the cylinders, so
	 * there is no threshold. A hand-add is the only way it reaches the list at all, which makes it
	 * the right subject for the defining test rather than a separate case bolted on at the end.
	 */
	private UUID gas;

	/** Food the regenerator likewise never suggests, held in grams rather than kilograms. */
	private UUID jaggery;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice", "Grains", "KG", false);
		gas = ingredient("Cooking gas cylinder", "Supplies", "PIECES", true);
		jaggery = ingredient("Jaggery", "Sweeteners", "GM", false);

		// Only rice is stocked, and it is below its threshold: 10 × 1.2 = 12, less 3 on hand = 9.
		admin.update("INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold) VALUES (?, ?, 10)",
				tenant, rice);
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, 3, 'KG', 'PO_RECEIPT', ?)
				""", tenant, rice, UUID.randomUUID(), staffId);

		// A preferred vendor for jaggery only, so the hand-add has one to find and rice's line does
		// not accidentally supply the answer.
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678') RETURNING id
				""", UUID.class, tenant);
		admin.update("INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, true)",
				tenant, vendor, jaggery);

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a hand-added line is still on the list after a real regeneration has run")
	void handAddedLineSurvivesARealRegeneration() throws Exception {
		// Deliberately no assertion on `edited` anywhere in this test. That column is the mechanism,
		// and a test that checked it here would fail on the mechanism rather than on the consequence
		// — which is exactly what the first run of this test's negative control did, reporting
		// "expected true but was false" without ever running the regeneration it exists to survive.
		// What is asserted below is that the line is still there afterwards. The column is asserted
		// once, on its own, in theEditedFlagIsWhatSavesIt.
		mvc.perform(add(gas, "2"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ingredientName").value("Cooking gas cylinder"))
				.andExpect(jsonPath("$.suggestedQty").value(2))
				.andExpect(jsonPath("$.unit").value("PIECES"))
				.andExpect(jsonPath("$.included").value(true));

		// The regeneration the nightly job runs, not a stand-in for it. It suggests one line — rice —
		// and then deletes every line it did not suggest and no human has touched. The cylinder is in
		// the first category and must be saved by the second.
		mvc.perform(regenerate()).andExpect(status().isOk()).andExpect(jsonPath("$.lines").value(1));

		// Ordered by ingredient name: "Cooking gas cylinder" then "Rice".
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].ingredientName").value("Cooking gas cylinder"))
				.andExpect(jsonPath("$[0].suggestedQty").value(2))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(9));
	}

	@Test
	@DisplayName("the line is marked edited, which is what carries it through the regeneration")
	void theEditedFlagIsWhatSavesIt() throws Exception {
		// The mechanism, asserted once and named for what it does. The screen reads the same column
		// to print its "edited" note beside the ingredient, so it is worth stating on its own —
		// but the fact that matters is the survival above, not this.
		mvc.perform(add(gas, "2"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.edited").value(true));
	}

	@Test
	@DisplayName("a hand-added line takes the ingredient's own unit and its preferred vendor")
	void takesTheCanonicalUnitAndThePreferredVendor() throws Exception {
		// Nothing in the request says "GM". The request has no unit field at all, and this is what
		// that decision buys: the line is written in the unit the ingredient is actually held in, so
		// there is no unit for a caller to get wrong and no way to ask a vendor for litres of jaggery.
		mvc.perform(add(jaggery, "500"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.unit").value("GM"))
				.andExpect(jsonPath("$.suggestedVendorName").value("Govind Wholesale"))
				// No stream drove it, so there is nothing to explain and no date to meet.
				.andExpect(jsonPath("$.shortfall").value(0))
				.andExpect(jsonPath("$.thresholdTopUp").value(0))
				.andExpect(jsonPath("$.poOutstanding").value(0))
				.andExpect(jsonPath("$.neededBy").doesNotExist());
	}

	@Test
	@DisplayName("adding something already on the list is refused with KMS-400131")
	void alreadyOnTheListIsRefused() throws Exception {
		mvc.perform(regenerate());   // rice is now on the list, suggested at 9 KG

		mvc.perform(add(rice, "4"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400131"));

		// The refusal left the line exactly as it was. This is the reason for refusing rather than
		// upserting: a silent overwrite would have replaced a computed 9 with a typed 4, and nobody
		// asked for that.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].suggestedQty").value(9))
				.andExpect(jsonPath("$[0].edited").value(false));
	}

	@Test
	@DisplayName("a hand-added line is refused a second time even after it was added by hand")
	void handAddedLineCannotBeAddedTwice() throws Exception {
		mvc.perform(add(gas, "2")).andExpect(status().isCreated());
		mvc.perform(add(gas, "3"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400131"));
	}

	@Test
	@DisplayName("a quantity of zero is refused rather than parked on the list as a placeholder")
	void zeroQuantityIsRefused() throws Exception {
		mvc.perform(add(gas, "0")).andExpect(status().isBadRequest());
		mvc.perform(authed(get("/api/v1/shopping-list"))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("an ingredient that does not exist is refused")
	void unknownIngredientIsRefused() throws Exception {
		mvc.perform(add(UUID.randomUUID(), "2")).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a volunteer cannot add a line")
	void volunteerCannotAdd() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(add(gas, "2")).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder add(UUID ingredientId, String qty) {
		return authed(post("/api/v1/shopping-list"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"ingredientId\":\"" + ingredientId + "\",\"suggestedQty\":" + qty + "}");
	}

	private MockHttpServletRequestBuilder regenerate() {
		return authed(post("/api/v1/shopping-list/regenerate"));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID ingredient(String name, String category, String unit, boolean supply) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_supply)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, name, category, unit, supply);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
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
