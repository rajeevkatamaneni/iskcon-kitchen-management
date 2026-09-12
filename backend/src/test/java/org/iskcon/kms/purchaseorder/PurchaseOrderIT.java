package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Purchase orders (E5-S3): creation — by hand, and from a vendor tile on the shopping list (T-134) —
 * the lifecycle guards (edit only a draft, valid transitions only), per-tenant monotonic numbering,
 * and the append-only activity trail.
 *
 * <p>Orders raised from the shopping list go through {@code POST /purchase-orders}, exactly as a
 * tile's Save does, via {@link #raiseFromTheList}. They used to go through
 * {@code POST /purchase-orders/generate}, which T-153 retired; {@link #generateIsGone} asserts that.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderIT.StubVerifierConfiguration.class)
class PurchaseOrderIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's own day, which is what a needed-by date is measured in. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID dal;
	private UUID sugar;
	private UUID vendorA;
	private UUID vendorB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice");
		dal = ingredient("Toor Dal");
		sugar = ingredient("Sugar");

		vendorA = vendor("Govind Wholesale");
		vendorB = vendor("Sri Traders");

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		// The shopping list is computed rather than stored (T-132), so this fixture builds real
		// demand — a reorder threshold, a preferred vendor, and where a date is wanted, a planned
		// meal. All of it holds the ingredient down through a foreign key and has to go first.
		admin.execute("DELETE FROM meal_plans");
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

	/**
	 * The bulk generator is gone, and its absence is asserted rather than assumed (T-153).
	 *
	 * <p>This replaces "three checked lines across two vendors generate exactly two correct draft
	 * POs", whose only subject was the generator grouping the whole list by vendor. That grouping now
	 * happens on the shopping-list screen, one tile per vendor, and is proved by
	 * {@code frontend/__tests__/shopping-list.test.tsx}; each tile raises its own order through
	 * {@code POST /purchase-orders}, which the tests below exercise.
	 *
	 * <p>The fixture puts real lines with a preferred vendor on the list first, and the body is the
	 * one the old endpoint accepted, so a route left registered would have something to create. It
	 * is answered as a wrong address — {@code KMS-400030} and a 404, the same answer
	 * {@code GlobalExceptionHandler} gives any withdrawn route — and no order exists afterwards.
	 */
	@Test
	@DisplayName("there is no generate endpoint any more, and posting to it creates nothing")
	void generateIsGone() throws Exception {
		orderLine(rice, "9", vendorA, "45.00");
		orderLine(sugar, "12", vendorB, "42.00");

		mvc.perform(authed(post("/api/v1/purchase-orders/generate"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ingredientIds\":[\"" + rice + "\",\"" + sugar + "\"]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
		mvc.perform(authed(post("/api/v1/purchase-orders/generate")))
				.andExpect(status().isNotFound());

		assert admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class) == 0
				: "nothing may be created through a retired route";
	}

	/**
	 * T-134. The shopping list's vendor tiles raise their orders through this endpoint and confirm
	 * each one by name — "PO-2026-0041 raised for Heritage Fresh Dairy" — so the number has to come
	 * back with the id. It used to answer with the id alone.
	 *
	 * <p>Checked against the order's own detail rather than a pattern alone, because a response that
	 * invented a plausible-looking number would satisfy a regular expression perfectly.
	 */
	@Test
	@DisplayName("a created order answers with the number a person can read out, not just its id")
	void createAnswersWithThePoNumber() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":5,\"unit\":\"KG\"}]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.poNumber").exists())
				.andReturn().getResponse().getContentAsString();

		JsonNode answer = JSON.readTree(body);
		String poNumber = answer.get("poNumber").asText();
		assert poNumber.matches("PO-\\d{4}-\\d{4}") : "expected PO-YYYY-nnnn, got " + poNumber;
		assert getDetail(answer.get("id").asText()).get("order").get("poNumber").asText().equals(poNumber)
				: "the number in the response must be the order's own";
	}

	/**
	 * T-134. Until the shopping list raised its orders one vendor tile at a time, only generation
	 * filled in an expected price: it looked the vendor's last-known figures up, and manual creation
	 * sent nulls. The tiles create their orders through the manual endpoint — it is the only one that
	 * can carry an adjusted quantity, an added line and a typed date — so without this the price
	 * column would have quietly vanished from every order the temple raises in the ordinary way.
	 *
	 * <p>Three lines, three answers, because "fills the price in" is not one behaviour: a blank line
	 * takes the vendor's last-known price, a line that brought its own keeps it, and a described line
	 * — four plastic stools — has no catalogue row to have a price on and stays null.
	 */
	@Test
	@DisplayName("a line raised without a price takes the vendor's last-known one, and only that line")
	void createFillsInTheVendorsLastKnownPrice() throws Exception {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, 45.00, true)
				""", tenant, vendorA, rice);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, 120.00, true)
				""", tenant, vendorA, dal);
		// Sugar is supplied by this vendor but nobody has ever bought it: no price is known, and none
		// is invented.
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, NULL, true)
				""", tenant, vendorA, sugar);

		String body = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":9,\"unit\":\"KG\"},"
								+ "{\"ingredientId\":\"" + dal + "\",\"quantity\":6,\"unit\":\"KG\",\"expectedPrice\":99.50},"
								+ "{\"ingredientId\":\"" + sugar + "\",\"quantity\":2,\"unit\":\"KG\"},"
								+ "{\"description\":\"Plastic stool\",\"quantity\":4,\"unit\":\"PIECES\"}]}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		JsonNode lines = getDetail(JSON.readTree(body).get("id").asText()).get("lines");
		Map<String, JsonNode> bySubject = new HashMap<>();
		for (JsonNode l : lines) {
			bySubject.put(l.get("ingredientName").isNull()
					? l.get("description").asText() : l.get("ingredientName").asText(), l);
		}
		assert bySubject.get("Rice").get("expectedPrice").asDouble() == 45.0
				: "a blank price takes the vendor's last-known one";
		assert bySubject.get("Toor Dal").get("expectedPrice").asDouble() == 99.5
				: "a price the caller sent is the caller's";
		assert bySubject.get("Sugar").get("expectedPrice").isNull()
				: "no last price known, so none invented";
		assert bySubject.get("Plastic stool").get("expectedPrice").isNull()
				: "a described line has no catalogue row to have a price on";
	}

	@Test
	@DisplayName("a sent purchase order can no longer be edited")
	void cannotEditAfterSend() throws Exception {
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "8")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400050"));
	}

	@Test
	@DisplayName("a draft takes a changed quantity, a new line, and a line taken away")
	void draftLinesAreEditable() throws Exception {
		String id = createManual(vendorA, rice, "5");

		// A quantity revised upwards, and a second ingredient remembered.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":["
								+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":8,\"unit\":\"KG\"},"
								+ "{\"ingredientId\":\"" + dal + "\",\"quantity\":3,\"unit\":\"KG\"}]}"))
				.andExpect(status().isNoContent());

		JsonNode after = getDetail(id);
		assert after.get("lines").size() == 2 : "the added line should be there";
		Map<String, Double> byName = new HashMap<>();
		after.get("lines").forEach(l -> byName.put(l.get("ingredientName").asText(), l.get("quantity").asDouble()));
		assert byName.get("Rice") == 8.0 : "the revised quantity should stand";
		assert byName.get("Toor Dal") == 3.0;
		assert after.get("order").get("status").asText().equals("DRAFT") : "editing does not advance a PO";

		// And the rice taken off again — a removal is simply a shorter line set.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(dal, "3")))
				.andExpect(status().isNoContent());

		JsonNode trimmed = getDetail(id);
		assert trimmed.get("lines").size() == 1 : "the removed line should be gone";
		assert trimmed.get("lines").get(0).get("ingredientName").asText().equals("Toor Dal");
		// Each edit leaves its own mark, so the trail says the draft was worked on twice.
		assert trimmed.get("events").findValues("eventType").stream()
				.filter(n -> n.asText().equals("EDITED")).count() == 2;
	}

	@Test
	@DisplayName("a draft purchase order cannot be received — that isn't a valid step")
	void cannotReceiveDraft() throws Exception {
		String id = createManual(vendorA, rice, "5");
		// Receiving is reached through the receiving endpoint (E5-S6); here we assert the guard by
		// attempting an out-of-order send after cancel, which is the same PO_INVALID_TRANSITION path.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"duplicate order\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400051"));
	}

	@Test
	@DisplayName("purchase-order numbers are sequential and never duplicated within a tenant")
	void numberingIsMonotonic() throws Exception {
		String id1 = createManual(vendorA, rice, "5");
		String id2 = createManual(vendorB, dal, "5");
		String id3 = createManual(vendorA, sugar, "5");

		String n1 = getDetail(id1).get("order").get("poNumber").asText();
		String n2 = getDetail(id2).get("order").get("poNumber").asText();
		String n3 = getDetail(id3).get("order").get("poNumber").asText();

		assert seq(n1) + 1 == seq(n2) : n1 + " -> " + n2;
		assert seq(n2) + 1 == seq(n3) : n2 + " -> " + n3;
	}

	@Test
	@DisplayName("cancelling records a reason and an event on the trail")
	void cancelLeavesTrail() throws Exception {
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"vendor out of stock\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(jsonPath("$.order.status").value("CANCELLED"))
				.andExpect(jsonPath("$.order.cancelReason").value("vendor out of stock"))
				.andExpect(jsonPath("$.events[?(@.eventType=='CANCELLED')]").exists());
	}

	@Test
	@DisplayName("a cancellation says nothing about the vendor unless somebody ticks the box")
	void anUntickedCancellationBlamesNobody() throws Exception {
		// The default, and it is the whole safety of the feature. A request that does not mention
		// the field at all — an older client, a script, every test written before T-124 — gets the
		// reading that blames nobody. Two defects this week came from boxes that were already
		// ticked, both recording things nobody meant to say.
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"festival moved\"}"))
				.andExpect(status().isNoContent());

		assert !abandonedFlag(id) : "an unticked cancellation must not be held against the vendor";
		// And the trail is the operator's own sentence, with nothing added to it.
		assert "festival moved".equals(cancelEventDetail(id)) : cancelEventDetail(id);
	}

	@Test
	@DisplayName("ticking the never-delivered box is recorded on the order, the trail and the audit")
	void tickingTheBoxNamesTheVendor() throws Exception {
		// Rajeev's fifth delivery scenario, 2026-09-09: "nothing ever came; we cancelled and went
		// elsewhere". The tick is the one new fact anybody enters for the whole of T-124, and it is
		// a permanent statement about somebody else's business — so it has to land in all three
		// places a person might later read it back from.
		// Sent first, because since T-129 there is nothing to hold a vendor to on an order they were
		// never sent, and the endpoint says so (KMS-400147).
		String id = createManual(vendorA, rice, "5");
		markSent(id);
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"never answered the phone\",\"vendorAbandoned\":true}"))
				.andExpect(status().isNoContent());

		assert abandonedFlag(id) : "the tick must reach purchase_orders.vendor_abandoned";

		// The reason survives verbatim, because the box carries the fact and the sentence carries
		// the story. The trail says both.
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(jsonPath("$.order.cancelReason").value("never answered the phone"));
		assert cancelEventDetail(id).contains("never answered the phone") : cancelEventDetail(id);
		assert cancelEventDetail(id).contains("never delivered by the vendor") : cancelEventDetail(id);

		// And the audit record's after-state, which is where a claim about a third party belongs:
		// who ticked it and when are already the audit actor and cancelled_at beside it.
		String after = admin.queryForObject(
				"SELECT after_state::text FROM audit_events WHERE entity_id = ?::uuid"
						+ " AND action = 'PO_CANCELLED'",
				String.class, id);
		assert after.contains("\"vendorAbandoned\": true") || after.contains("\"vendorAbandoned\":true")
				: after;
	}

	@Test
	@DisplayName("a cancelled order's own screen can read back whether the vendor was blamed")
	void theOrderViewCarriesTheNeverDeliveredFlag() throws Exception {
		// T-126. T-124 wrote the tick into the row, the trail and the audit record and scored the
		// vendor 0% for it — but not into the view the order screen renders, so the one screen where
		// somebody asks "why was this cancelled?" could not answer it without reading the trail.
		//
		// Both readings are asserted from the same endpoint, because a flag that is only ever
		// checked when true proves nothing about the far commoner case: a cancellation for our own
		// reasons must come back saying so, not saying nothing.
		String blamed = createManual(vendorA, rice, "5");
		markSent(blamed);
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", blamed))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"never answered the phone\",\"vendorAbandoned\":true}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", blamed)))
				.andExpect(jsonPath("$.order.vendorAbandoned").value(true))
				.andExpect(jsonPath("$.order.cancelReason").value("never answered the phone"));

		String ours = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", ours))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"festival moved\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", ours)))
				.andExpect(jsonPath("$.order.vendorAbandoned").value(false));

		// And an order nobody has cancelled at all reads false rather than absent — the list is the
		// other reader of this view, and a missing key there would render as "not a no-show" by
		// accident rather than on purpose.
		String live = createManual(vendorA, rice, "5");
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", live)))
				.andExpect(jsonPath("$.order.vendorAbandoned").value(false));
		mvc.perform(authed(get("/api/v1/purchase-orders")))
				.andExpect(jsonPath("$[?(@.id=='" + blamed + "')].vendorAbandoned").value(true));
	}

	@Test
	@DisplayName("an order nobody sent cannot be cancelled as one the vendor never delivered")
	void aNeverSentOrderCannotBlameTheVendor() throws Exception {
		// Rajeev's ruling of 2026-09-10 (T-129), taken from three options. The coordinator raised
		// PO-2026-0036 as a draft on staging, never pressed Mark sent, cancelled it with the box
		// ticked, and Heritage Fresh Dairy's scorecard then read "0% on time, 1 order never
		// delivered" for an order the vendor had never heard of.
		//
		// The refusal is asserted here and not only on the cancel panel, because the panel hiding
		// the box is not what stops this: the endpoint takes the same field from anything that can
		// post to it.
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"never answered the phone\",\"vendorAbandoned\":true}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400147"));

		// And the refusal is total: the order is still live, with no cancellation half-applied.
		// A guard that threw after the UPDATE would leave the row cancelled and the caller told it
		// had failed, which is the worse of the two possible bugs here.
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(jsonPath("$.order.status").value("DRAFT"))
				.andExpect(jsonPath("$.order.vendorAbandoned").value(false))
				.andExpect(jsonPath("$.order.cancelReason").isEmpty());
	}

	@Test
	@DisplayName("an unsent order is still perfectly cancellable, just not against the vendor")
	void aNeverSentOrderCancelsNormally() throws Exception {
		// The other half of the ruling, and the half a guard like this usually breaks. What was
		// removed is one claim a person may make, never the ability to call an order off — a draft
		// raised against the wrong vendor still has to go somewhere.
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"raised against the wrong vendor\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(jsonPath("$.order.status").value("CANCELLED"))
				.andExpect(jsonPath("$.order.vendorAbandoned").value(false));
	}

	@Test
	@DisplayName("marking an order sent is what opens the never-delivered box, and nothing else")
	void sendingIsWhatMakesTheVendorAnswerable() throws Exception {
		// The two requests differ in exactly one thing: whether Mark sent was pressed in between.
		// Asserting the refusal alone would leave "the endpoint refuses this always" as an equally
		// good explanation of a green run.
		String id = createManual(vendorA, rice, "5");
		String body = "{\"reason\":\"never answered the phone\",\"vendorAbandoned\":true}";

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400147"));

		markSent(id);

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isNoContent());
		assert abandonedFlag(id) : "once the order has been sent, the tick is allowed to land";
	}

	@Test
	@DisplayName("the never-delivered flag cannot be set on an order that is not cancelled")
	void theFlagIsStructurallyTiedToACancellation() throws Exception {
		// A claim that a vendor never delivered an order still in progress would score them nothing
		// for a delivery that has not happened yet. The schema refuses it rather than trusting every
		// future writer to remember (purchase_orders_abandoned_is_a_cancellation).
		String id = createManual(vendorA, rice, "5");
		boolean refused = false;
		try {
			admin.update("UPDATE purchase_orders SET vendor_abandoned = true WHERE id = ?::uuid", id);
		}
		catch (org.springframework.dao.DataIntegrityViolationException expected) {
			refused = true;
		}
		assert refused : "a live order must not be markable as abandoned";
	}

	@Test
	@DisplayName("a line measured in something the ingredient can't be measured in is refused")
	void crossFamilyLineIsRefused() throws Exception {
		UUID ghee = ingredient("Ghee", "L");

		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + ghee + "\",\"quantity\":5,\"unit\":\"KG\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Ghee"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("Ghee is measured in L, and there is no way to turn Kg into L."));

		assert admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class) == 0
				: "the order is refused whole, not written and then repaired";
	}

	@Test
	@DisplayName("pieces convert to nothing: a weight against a counted ingredient is refused")
	void piecesAreTheirOwnFamily() throws Exception {
		UUID coconut = ingredient("Coconut", "PIECES");

		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + coconut + "\",\"quantity\":30,\"unit\":\"KG\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("Coconut is measured in pieces, and there is no way to turn Kg into pieces."));
	}

	@Test
	@DisplayName("a different unit of the same family is fine, and is stored as it was given")
	void sameFamilyDifferentUnitIsAccepted() throws Exception {
		// Rice is held in Kg. Ordering 2,500 gm of it is a perfectly ordinary thing to write, and
		// converts: same family is the rule, not same unit.
		String body = "{\"vendorId\":\"" + vendorA + "\",\"lines\":["
				+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":2500,\"unit\":\"GM\"}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		JsonNode po = getDetail(JSON.readTree(response).get("id").asText());
		assert po.get("lines").get(0).get("unit").asText().equals("GM") : "the line keeps its own unit";
		// And the database's own conversion agrees: 2,500 gm is 2,500 base grams, which is 2.5 Kg.
		assert admin.queryForObject("SELECT to_base_qty(quantity, unit) FROM purchase_order_lines",
				java.math.BigDecimal.class).compareTo(new java.math.BigDecimal("2500")) == 0;
	}

	@Test
	@DisplayName("a draft edited into a cross-family line is refused, and the draft is left as it was")
	void editIntoACrossFamilyLineIsRefused() throws Exception {
		UUID ghee = ingredient("Ghee", "L");
		String id = createManual(vendorA, rice, "5");

		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[{\"ingredientId\":\"" + ghee
								+ "\",\"quantity\":5,\"unit\":\"KG\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"));

		// The edit replaces the line set wholesale, so a refusal half-way would have left the draft
		// with no lines at all. It rolls back instead.
		JsonNode after = getDetail(id);
		assert after.get("lines").size() == 1 : "the draft keeps the lines it had";
		assert after.get("lines").get(0).get("ingredientName").asText().equals("Rice");
	}

	@Test
	@DisplayName("a draft's needed-by date can be set, changed, and cleared again")
	void neededByIsEditableOnADraft() throws Exception {
		String id = createManual(vendorA, rice, "5");
		assert getDetail(id).get("order").get("neededBy").isNull() : "nothing was asked for yet";

		LocalDate wanted = LocalDate.now(TEMPLE_ZONE).plusDays(6);
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + wanted + "\"")))
				.andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("neededBy").asText().equals(wanted.toString());

		// Changed again, to a nearer day. Inside the two-day buffer, and accepted: the buffer warns
		// on the screen and never refuses, because a temple that needs rice tomorrow may say so.
		LocalDate tomorrow = LocalDate.now(TEMPLE_ZONE).plusDays(1);
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + tomorrow + "\"")))
				.andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("neededBy").asText().equals(tomorrow.toString());

		// And cleared. The column is nullable and an order with no date to meet is a real order —
		// E5-S9 counts those aside rather than judging them, so this must stay expressible.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "null")))
				.andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("neededBy").isNull() : "a cleared date is a cleared date";
	}

	@Test
	@DisplayName("a needed-by date behind the order's own date is refused")
	void neededByBeforeOrderDateIsRefused() throws Exception {
		String id = createManual(vendorA, rice, "5");
		// Read from the order rather than from a clock: the floor is the order's own date, and the
		// order is dated in the temple's day whatever zone the machine running this happens to be in.
		LocalDate dayBefore = LocalDate
				.parse(getDetail(id).get("order").get("orderDate").asText()).minusDays(1);

		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + dayBefore + "\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400014"));

		assert getDetail(id).get("order").get("neededBy").isNull() : "the refusal wrote nothing";
	}

	@Test
	@DisplayName("an order is dated the temple's own day, not the machine's")
	void orderDateIsTheTemplesDay() throws Exception {
		String id = createManual(vendorA, rice, "5");
		assert getDetail(id).get("order").get("orderDate").asText()
				.equals(LocalDate.now(TEMPLE_ZONE).toString())
				: "the kitchen's day is the operational day, wherever the server runs";
	}

	@Test
	@DisplayName("the needed-by date is frozen once the order has been sent")
	void neededByCannotBeChangedAfterSending() throws Exception {
		String id = createManual(vendorA, rice, "5");
		LocalDate wanted = LocalDate.now(TEMPLE_ZONE).plusDays(6);
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + wanted + "\"")))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		// The vendor has been told this date and the scorecard measures them against it. Moving it
		// now would rewrite their record after the fact, so the endpoint refuses — the screen hiding
		// the field is not what stops this.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + wanted.plusDays(10) + "\"")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400050"));

		assert getDetail(id).get("order").get("neededBy").asText().equals(wanted.toString())
				: "the date the vendor was given still stands";
	}

	/**
	 * An order raised from a vendor's lines on the shopping list is dated for the first of them, and
	 * carries the vendor's last-known prices although the tile sent none.
	 *
	 * <p><strong>Re-routed in T-153, and what moved.</strong> This was "generation still takes the
	 * earliest needed-by across a vendor's lines", asserted through the retired generator, which
	 * worked the minimum out on the server. Since T-134 that rule lives in the tile's panel — it
	 * opens with the earliest date the vendor's lines carry, proved in
	 * {@code shopping-list.test.tsx} — and the order is created through {@code POST /purchase-orders}
	 * with that date. What the server still owns, and this asserts through the path the tiles use:
	 * the list gives each line its own date from demand, a two-line order posted with the earliest
	 * keeps it, and {@code createPo} fills both blank prices from {@code vendor_supplies.last_price}.
	 * That last part was the price assertion in the removed per-vendor grouping test; it is kept here,
	 * on an order that really came off the list, rather than lost with that test.
	 */
	@Test
	@DisplayName("an order raised from the list takes the earliest needed-by of its lines, and their last prices")
	void anOrderRaisedFromTheListTakesTheEarliestNeededBy() throws Exception {
		LocalDate soon = LocalDate.now(TEMPLE_ZONE).plusDays(3);
		LocalDate later = LocalDate.now(TEMPLE_ZONE).plusDays(9);
		orderLine(rice, "9", vendorA, "45.00", soon);
		orderLine(dal, "6", vendorA, "120.00", later);

		JsonNode po = getDetail(raiseFromTheList(vendorA));

		assert po.get("order").get("neededBy").asText().equals(soon.toString())
				: "the order is only useful if it arrives for the first meal that wants any of it";
		assert po.get("order").get("status").asText().equals("DRAFT");
		assert po.get("lines").size() == 2 : "both of the vendor's lines are on its one order";
		Map<String, JsonNode> byName = new HashMap<>();
		for (JsonNode l : po.get("lines")) {
			byName.put(l.get("ingredientName").asText(), l);
		}
		assert byName.get("Rice").get("expectedPrice").asDouble() == 45.0
				: "a line raised from the list takes the vendor's last-known price";
		assert byName.get("Toor Dal").get("expectedPrice").asDouble() == 120.0
				: "a line raised from the list takes the vendor's last-known price";
	}

	/**
	 * <strong>This test used to assert the opposite, and T-130 is why it changed.</strong> The
	 * shopping list subtracted a two-day delivery buffer from the first meal that wanted an
	 * ingredient, so a meal planned for tomorrow produced a needed-by of yesterday — and the test
	 * here recorded that generation accepted it, because refusing a computed date would have broken
	 * the list rather than protected anything.
	 *
	 * <p>That buffer was a guess standing in for a fact the product did not yet have. T-090 recorded
	 * the real lead time per vendor and ingredient, and it is applied on the other side of the
	 * question — the last day the temple can ask, not the date it writes on the order. Subtracting
	 * both meant asking a supplier to deliver two days before the food was needed, and then warning
	 * on this very screen that the same date gave the supplier too little notice.
	 *
	 * <p>So the case that needed excusing no longer exists: the demand query only looks at meals
	 * from today onwards, and the date is now the meal's own day. What is asserted is that, which is
	 * a stronger statement than the one it replaces. The floor on a typed date is still real and
	 * still tested.
	 *
	 * <p><strong>Re-routed in T-153.</strong> The order used to come from the retired generator, which
	 * was exempt from that floor. It now comes through {@code POST /purchase-orders}, as a tile's Save
	 * sends it, and that path <em>is</em> held to the floor ({@code KMS-400014}) — so this also proves
	 * the date the list works out for tomorrow's meal is one the tile can actually save.
	 */
	@Test
	@DisplayName("an order raised from the list is dated for the meal itself, and the floor lets it through")
	void anOrderRaisedFromTheListIsDatedForTheMealItself() throws Exception {
		LocalDate meal = LocalDate.now(TEMPLE_ZONE).plusDays(1);
		orderLine(rice, "9", vendorA, "45.00", meal);

		String id = raiseFromTheList(vendorA);
		assert getDetail(id).get("order").get("neededBy").asText().equals(meal.toString())
				: "the order asks for delivery on the day of the meal, not two days before it";
	}

	@Test
	@DisplayName("a volunteer cannot manage purchase orders")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/purchase-orders"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	/**
	 * Presses "Mark sent", which is the only thing in the application that stamps
	 * {@code purchase_orders.sent_at} — and therefore the only thing that makes a vendor answerable
	 * for the order at all (T-129).
	 */
	private void markSent(String id) throws Exception {
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
	}

	private String createManual(UUID vendor, UUID ingredient, String qty) throws Exception {
		String body = "{\"vendorId\":\"" + vendor + "\",\"lines\":["
				+ "{\"ingredientId\":\"" + ingredient + "\",\"quantity\":" + qty + ",\"unit\":\"KG\"}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	private String lineBody(UUID ingredient, String qty) {
		return "{\"lines\":[{\"ingredientId\":\"" + ingredient + "\",\"quantity\":" + qty + ",\"unit\":\"KG\"}]}";
	}

	/** The same body with a needed-by date on it — {@code neededByJson} is a quoted date, or null. */
	private String lineBody(UUID ingredient, String qty, String neededByJson) {
		return "{\"neededBy\":" + neededByJson + ",\"lines\":[{\"ingredientId\":\"" + ingredient
				+ "\",\"quantity\":" + qty + ",\"unit\":\"KG\"}]}";
	}

	private JsonNode getDetail(String id) throws Exception {
		String body = mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body);
	}

	/**
	 * Raises one vendor's order from the shopping list the way the vendor's tile does (T-134): read
	 * the list as the screen reads it, take that vendor's included lines with the quantity and unit
	 * the list suggests, open with the earliest needed-by any of them carries, send no price, and
	 * post it to {@code POST /purchase-orders}. Nothing here is generated on the server — that route
	 * was retired in T-153 — so the test exercises the only door an order now comes through.
	 *
	 * @return the created order's id
	 */
	private String raiseFromTheList(UUID vendor) throws Exception {
		String list = mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		ObjectNode order = JSON.createObjectNode();
		order.put("vendorId", vendor.toString());
		order.put("notes", "Generated from the shopping list");
		ArrayNode lines = order.putArray("lines");
		LocalDate earliest = null;
		for (JsonNode l : JSON.readTree(list)) {
			if (!l.get("included").asBoolean() || !vendor.toString().equals(l.get("suggestedVendorId").asText())) {
				continue;
			}
			ObjectNode line = lines.addObject();
			line.put("ingredientId", l.get("ingredientId").asText());
			line.set("quantity", l.get("suggestedQty"));
			line.put("unit", l.get("unit").asText());
			if (!l.get("neededBy").isNull()) {
				LocalDate d = LocalDate.parse(l.get("neededBy").asText());
				earliest = earliest == null || d.isBefore(earliest) ? d : earliest;
			}
		}
		assert lines.size() > 0 : "the list should carry at least one line for this vendor";
		order.put("neededBy", earliest == null ? null : earliest.toString());

		String created = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(order.toString()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(created).get("id").asText();
	}

	/** Whether this order was cancelled with "Vendor Never Delivered this Order" ticked (T-124). */
	private boolean abandonedFlag(String id) {
		return Boolean.TRUE.equals(admin.queryForObject(
				"SELECT vendor_abandoned FROM purchase_orders WHERE id = ?::uuid", Boolean.class, id));
	}

	/** The line the cancellation left on the order's own activity trail. */
	private String cancelEventDetail(String id) {
		return admin.queryForObject(
				"SELECT detail FROM po_events WHERE po_id = ?::uuid AND event_type = 'CANCELLED'",
				String.class, id);
	}

	private static int seq(String poNumber) {
		return Integer.parseInt(poNumber.substring(poNumber.lastIndexOf('-') + 1));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID ingredient(String name) {
		return ingredient(name, "KG");
	}

	private UUID ingredient(String name, String canonicalUnit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, canonicalUnit);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, tenant, name);
	}

	/**
	 * Puts one line on the shopping list, by giving the temple a reason to want the thing.
	 *
	 * <p><strong>This used to insert straight into {@code shopping_list_lines}, and that was the most
	 * dangerous thing in this file.</strong> The table survived T-132 — it holds human decisions now
	 * — so those inserts went on succeeding and every test here stayed green while the shopping-list
	 * generator (retired since, in T-153) was reading a table that no longer held the list. A suite that
	 * passes while the product is broken is worse than one that fails, because nothing tells you.
	 *
	 * <p>So the fixture builds real demand instead: a reorder threshold the store room has fallen
	 * below, and a preferred vendor to order from. The suggested quantity is the reorder level × the
	 * 1.2 safety factor, rounded up, with nothing on hand — so a threshold of 7.5 asks for 9. That
	 * arithmetic is stated here rather than hidden, because a test that wants a particular number on
	 * an order has to say where the number came from.
	 *
	 * @param qty the quantity the shopping list should end up suggesting, in KG
	 */
	private void orderLine(UUID ingredient, String qty, UUID vendor, String lastPrice) {
		orderLine(ingredient, qty, vendor, lastPrice, null);
	}

	/**
	 * As above, and additionally plans a meal on {@code neededBy} that demands the ingredient, so the
	 * line carries that date. Since T-130 the needed-by date on a line is the day of the earliest
	 * planned meal that wants it, with nothing subtracted.
	 */
	private void orderLine(UUID ingredient, String qty, UUID vendor, String lastPrice, LocalDate neededBy) {
		// reorder_threshold × 1.2, ceiling, less nothing on hand, is what the list will suggest.
		BigDecimal threshold = new BigDecimal(qty)
				.divide(new BigDecimal("1.2"), 6, java.math.RoundingMode.HALF_UP);
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric)
				""", tenant, ingredient, threshold.toPlainString());
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, true)
				""", tenant, vendor, ingredient, lastPrice);
		if (neededBy != null) {
			planAMealDemanding(ingredient, neededBy);
		}
	}

	/**
	 * A planned meal that wants one gram of the ingredient on a given day — enough to give the line a
	 * demand date without disturbing the quantity, which the reorder threshold decides.
	 */
	private void planAMealDemanding(UUID ingredient, LocalDate on) {
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, ?) RETURNING id
				""", UUID.class, tenant, "Course " + UUID.randomUUID());
		UUID recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, "Dish " + UUID.randomUUID(), category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 0.001, 'KG', 0)
				""", tenant, recipe, ingredient);
		admin.update("""
				INSERT INTO meal_plans (
					tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield, day_type, status, created_by)
				VALUES (?, ?, 'Lunch', TIME '12:00', ?, 1, 'REGULAR', 'PLANNED',
					(SELECT id FROM users WHERE tenant_id = ? AND firebase_uid = 'uid-staff-a'))
				""", tenant, on, recipe, tenant);
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
