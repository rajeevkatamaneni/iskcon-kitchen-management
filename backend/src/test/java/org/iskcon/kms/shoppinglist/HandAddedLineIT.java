package org.iskcon.kms.shoppinglist;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * <p><strong>The defining test is {@link #handAddedLineSurvivesAFreshDerivation()}, and it is
 * written against the list itself rather than against the column behind it.</strong> The column is
 * only the mechanism; the fact that has to hold is that the line is still on the list the next time
 * anybody opens the screen. Asserting {@code hand_added == true} would pass just as happily if the
 * derivation were later changed to ignore the column, and the failure it would miss is silent — a
 * line the cook typed, gone by morning, with nobody watching.
 *
 * <p><strong>What changed in T-132, and why the test reads the same.</strong> The list used to be a
 * stored table that a nightly job rewrote, deleting every line it had not just suggested and that no
 * human had touched; a hand-added line was saved by {@code edited = true}. The list is computed on
 * every read now, so there is no delete to survive — the danger is the opposite one, that a line
 * nothing suggests is simply never produced. {@code hand_added} is what produces it, and the
 * assertion is unchanged because the fact is: type it in, come back, it is there.
 *
 * <p>The fixture is built so that nothing here can be an artefact of the derivation. Rice has an
 * inventory row below its threshold, so the list suggests it of its own accord and there is real
 * work in every read. The gas cylinder and the jaggery have no inventory row, no recipe and no meal
 * plan, so no demand stream can ever reach them: if they are on the list, a person put them there.
 */
@AutoConfigureMockMvc
@Import(HandAddedLineIT.StubVerifierConfiguration.class)
class HandAddedLineIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;

	/** Food. Below its reorder threshold, so the list suggests it of its own accord. */
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

	/** Food no demand stream reaches either, held in grams rather than kilograms. */
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
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM audit_events");
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
	@DisplayName("a hand-added line is still on the list the next time it is computed")
	void handAddedLineSurvivesAFreshDerivation() throws Exception {
		// Deliberately no assertion on the marker column anywhere in this test. That column is the
		// mechanism, and a test that checked it here would fail on the mechanism rather than on the
		// consequence — which is exactly what the first run of this test's negative control did,
		// reporting "expected true but was false" without ever reaching the list it exists to
		// appear on. What is asserted below is that the line is still there. The column is asserted
		// once, on its own, in theMarkerIsWhatPutsItThere.
		mvc.perform(add(gas, "2"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ingredientName").value("Cooking gas cylinder"))
				.andExpect(jsonPath("$.suggestedQty").value(2))
				.andExpect(jsonPath("$.unit").value("PIECES"))
				.andExpect(jsonPath("$.included").value(true));

		// A fresh read, which is the only kind there is: the whole list is worked out again, from the
		// meal plan, the store room and the live orders. Exactly one line comes out of that — rice —
		// and nothing in it will ever reach a gas cylinder. The cylinder is on the list below
		// because a person put it there and the derivation carries it in on the strength of that.

		// Ordered by ingredient name: "Cooking gas cylinder" then "Rice".
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].ingredientName").value("Cooking gas cylinder"))
				.andExpect(jsonPath("$[0].suggestedQty").value(2))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(9));
	}

	@Test
	@DisplayName("the line comes back marked as a decision, which is what puts it on the list at all")
	void theMarkerIsWhatPutsItThere() throws Exception {
		// The mechanism, asserted once and named for what it does. `edited` on the view means "a
		// person has decided something about this line", which since T-132 is exactly "there is a
		// row for this ingredient", and the screen reads it to print its "edited" note. The fact
		// that matters is the survival above, not this.
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
		// Rice is on the list at 9 KG and there is no row behind it — the derivation put it there.
		// That is the case this refusal has to catch now, and it is the one a check against the
		// decision table alone would have missed: the unique index cannot refuse a conflict with a
		// line that was never written down. Somebody would have got a second Rice beside the one
		// already on their screen.
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

	/**
	 * A hand-added line leaves the list when a purchase order covers it, exactly as a suggested one
	 * does (D-24a). Worth its own test because a hand-added line is the one row that renders on its
	 * own strength, so it is the one that could plausibly have been left behind — and a cylinder
	 * still sitting on the list after somebody ordered it is precisely the "same ingredients, 2 PO's"
	 * confusion the ruling exists to prevent.
	 *
	 * <p>The order is raised as the jaggery vendor's tile raises it, through
	 * {@code POST /purchase-orders}. Until T-153 it went through {@code POST /purchase-orders/generate}
	 * with the jaggery's id; that route was retired, and the hand-added line is now proved to leave on
	 * the path the screen actually uses.
	 */
	@Test
	@DisplayName("a hand-added line goes off the list once an order covers it")
	void aHandAddedLineLeavesOnAnOrder() throws Exception {
		mvc.perform(add(jaggery, "500")).andExpect(status().isCreated());
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Jaggery')]").exists());

		raiseOrderFor("Jaggery");

		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Jaggery')]").doesNotExist())
				// Rice is untouched: it has no preferred vendor here, so it is on no order.
				.andExpect(jsonPath("$[?(@.ingredientName=='Rice')]").exists());
	}

	@Test
	@DisplayName("a quantity of zero is refused rather than parked on the list as a placeholder")
	void zeroQuantityIsRefused() throws Exception {
		mvc.perform(add(gas, "0")).andExpect(status().isBadRequest());
		// The gas is not on the list, which is the fact. It is asserted that way rather than as an
		// empty list, because the list is no longer empty until somebody builds it: rice is below
		// its reorder threshold and the derivation says so on every read, with or without this
		// refusal. Asserting a length of zero here would have been asserting that nothing had been
		// computed yet, which was true of the stored version and is true of nothing now.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Cooking gas cylinder')]").doesNotExist())
				.andExpect(jsonPath("$[?(@.ingredientName=='Rice')]").exists());
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

	/**
	 * Raises an order for one line of the list the way that line's vendor tile does (T-134): read the
	 * list as the screen reads it, take the line's own vendor, suggested quantity, unit and needed-by,
	 * send no price, and post it to {@code POST /purchase-orders} — the only way an order is created
	 * since T-153 retired {@code POST /purchase-orders/generate}.
	 */
	private void raiseOrderFor(String ingredientName) throws Exception {
		String list = mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode line = null;
		for (JsonNode l : JSON.readTree(list)) {
			if (ingredientName.equals(l.get("ingredientName").asText())) {
				line = l;
			}
		}
		assert line != null : ingredientName + " should be on the list to be ordered";
		assert !line.get("suggestedVendorId").isNull() : ingredientName + " needs a vendor to order from";

		ObjectNode order = JSON.createObjectNode();
		order.put("vendorId", line.get("suggestedVendorId").asText());
		order.set("neededBy", line.get("neededBy"));
		order.put("notes", "Generated from the shopping list");
		ObjectNode orderLine = order.putArray("lines").addObject();
		orderLine.put("ingredientId", line.get("ingredientId").asText());
		orderLine.set("quantity", line.get("suggestedQty"));
		orderLine.put("unit", line.get("unit").asText());

		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(order.toString()))
				.andExpect(status().isCreated());
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
