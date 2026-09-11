package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Closing a part-delivered order, and the score nobody may edit (T-142, D-26).
 *
 * <p>Rajeev's scenario, walked through rather than answered as posed: 500 kg of rice ordered, the
 * vendor has 300 on hand and <em>sends it immediately so the kitchen can cook</em>, expecting stock
 * in two days for the rest. <em>"The 200 KG should still be tied to the PO that raised and sent the
 * 500KG rice order and it should sit in a partially delivered state and the clock keeps
 * ticking."</em>
 *
 * <p>What these tests guard:
 *
 * <ul>
 *   <li>that closing is the only door out of a part delivery, and that it is offered nowhere else;
 *   <li>that the balance is <strong>released</strong> to the shopping list at the close and not
 *       before — asserted end to end in {@code ReceivingIT}, which used to assert the opposite, and
 *       here at the level of the derived list;
 *   <li>the two named endings, both his: <em>the vendor let us down</em> counts as computed and is
 *       recorded, <em>they fell short but made it right</em> takes the order out of the supplier's
 *       figures with the reason on it;
 *   <li>that anything other than "as computed" requires a sentence;
 *   <li><strong>that the computed score is shown and cannot be moved</strong> — there is no request
 *       anywhere that changes it, which is what {@code theScoreIsShownAndThereIsNoWayToMoveIt}
 *       asserts by trying;
 *   <li>that the exclusions are visible as counts beside the percentages; and
 *   <li>that a closed order is terminal to everything that could otherwise touch it.
 * </ul>
 *
 * <p>One class rather than four, deliberately: every {@code @Import} is part of the Spring context
 * cache key, and a suite that spawns a fresh context per small test class is how CI comes to die of
 * heap with nothing failing.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderClosingIT.StubVerifierConfiguration.class)
class PurchaseOrderClosingIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's own day, which is the only day any of this is measured in. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private PurchaseOrderAutoCancelRunner autoCancel;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID vendor;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		today = LocalDate.now(TEMPLE_ZONE);
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081',
					'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The release ----------------------------------------------------

	@Test
	@DisplayName("the balance stays with the vendor while the order is live, and is released when it is closed")
	void closingReleasesTheBalanceToTheShoppingList() throws Exception {
		// 500 kg of rice, 300 of it delivered. Rajeev's own numbers.
		String id = partDelivered(new BigDecimal("500"), new BigDecimal("300"), today.minusDays(4));

		// Nothing on the list. The vendor still owes 200 kg and the clock is ticking on them, so
		// suggesting rice here would have the temple ordering what a supplier is already bringing.
		assert !shoppingListHas("Rice") : "a part-delivered order still covers its ingredients";

		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());

		// And back, at the balance, naming the order that fell short. Nothing was written to the
		// shopping list to do it — the list is derived on every read (T-132), so "released" is this
		// order leaving one predicate and entering another.
		JsonNode line = shoppingListLine("Rice");
		assert line != null : "closing releases the remainder";
		assert line.get("poOutstanding").asInt() == 200 : line.toString();
		assert line.get("shortPurchaseOrders").get(0).asText().equals(poNumber(id)) : line.toString();
	}

	/**
	 * The suppression, tested against a demand that has nothing to do with the order.
	 *
	 * <p>{@code closingReleasesTheBalanceToTheShoppingList} above is about the <em>re-feed</em> — the
	 * balance coming back with the PO named beside it — and it would pass with the suppression gone,
	 * because nothing else in that fixture wants rice. This one gives the temple a reason of its own:
	 * the store room is below its reorder level, so the threshold stream asks for rice on every read
	 * regardless of any purchase order.
	 *
	 * <p>It must still be silent while the order is live. That is D-26's answer to the objection put
	 * against it — a temple short of rice would see nothing telling them so — and the answer is that
	 * they see the purchase order, past due and asking for a decision, which is better than a
	 * shopping-list line <strong>because it names the vendor who owes it</strong>. Suggesting rice
	 * here would have somebody raise a second order for what a supplier is already bringing, which is
	 * the exact confusion D-24a exists to prevent: <em>"Same ingredients, 2 PO's."</em>
	 */
	@Test
	@DisplayName("a part-delivered order silences the list even where the store room is asking for the same thing")
	void aLiveShortfallIsStillSuppressedWhileTheVendorOwesIt() throws Exception {
		// Below the reorder level, with no stock at all: the threshold stream wants rice whatever
		// any purchase order says.
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, 50)
				""", tenant, rice);
		assert shoppingListHas("Rice") : "the fixture has to be asking for rice before this means anything";

		String id = partDelivered(new BigDecimal("500"), new BigDecimal("300"), today.minusDays(4));
		assert !shoppingListHas("Rice")
				: "the vendor still owes 200 kg, so the list must not ask for rice they are bringing";

		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());
		assert shoppingListHas("Rice") : "and the decision releases it";
	}

	@Test
	@DisplayName("a closed order stops asking once somebody has raised another for the same thing")
	void theReleaseStopsOnceItHasBeenActedOn() throws Exception {
		String id = partDelivered(new BigDecimal("500"), new BigDecimal("300"), today.minusDays(4));
		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());
		assert shoppingListHas("Rice") : "released";

		// The person reads the list and raises the replacement order. From that moment the closed
		// order has been answered and must stop asking — otherwise its 200 kg would be re-fed on
		// every read for ever, and the day the replacement is delivered and received in full the
		// list would ask for 200 kg of rice the temple is standing on.
		//
		// Raised and not delivered, which is D-24a's own rule for every other line on this list:
		// "someone else will take pity and generate another PO. Same ingredients, 2 PO's."
		String replacement = order(new BigDecimal("200"), today.plusDays(3));
		assert !shoppingListHas("Rice") : "the live replacement covers it";

		admin.update("UPDATE purchase_orders SET status = 'RECEIVED' WHERE id = ?::uuid", replacement);
		assert !shoppingListHas("Rice")
				: "the closed order must not start asking again once its replacement has landed";
	}

	// ---- The two endings, both his --------------------------------------

	@Test
	@DisplayName("the vendor let us down: scored on what arrived, and recorded as their doing")
	void theVendorLetUsDownIsScoredAsComputedAndSaidSo() throws Exception {
		// Six of ten kilos there in time. T-124 scores on-time by quantity, so this is 60% before
		// anybody says anything about it, and closing changes no arithmetic at all.
		String id = judgeable(new BigDecimal("10"), new BigDecimal("6"));

		close(id, "VENDOR_LET_US_DOWN", "Rang four times over a fortnight. Nobody rang back.")
				.andExpect(status().isNoContent());

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(60))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(60))
				// Nothing is set aside. This ending is the one that costs the supplier, and it
				// costs them by leaving the figures alone.
				.andExpect(jsonPath("$.vendors[0].ordersExcused").value(0));

		JsonNode order = detail(id).get("order");
		assert order.get("status").asText().equals("CLOSED") : order.toString();
		assert order.get("closeOutcome").asText().equals("VENDOR_LET_US_DOWN") : order.toString();
		// The sentence survives verbatim, which is the whole reason it is required: a permanent
		// claim about somebody else's business with no explanation beside it is the record nobody
		// can read back in a year.
		assert order.get("closeNote").asText().startsWith("Rang four times") : order.toString();
		assert order.get("cancelledAt").isNull() : "closing is not a cancellation";
		assert !order.get("vendorAbandoned").asBoolean() : "they delivered 6 kg; they are no no-show";

		String line = trail(id, "CLOSED");
		assert line.contains("closed with part of it never delivered") : line;
		assert line.contains("back on the shopping list") : line;
		assert line.contains("Rang four times") : line;
	}

	@Test
	@DisplayName("they fell short but made it right: out of the on-time figure AND out of the fill rate, with the count shown")
	void theShortfallExcusedLeavesBothFigures() throws Exception {
		String id = judgeable(new BigDecimal("10"), new BigDecimal("6"));

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(60))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(60));

		close(id, "SHORTFALL_EXCUSED",
				"Rang the same afternoon, blamed the lorry, 10% off the next load.")
				.andExpect(status().isNoContent());

		mvc.perform(report())
				// Still placed — the order happened and the report must reconcile.
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(0))
				// Both figures, and this is the difference from D-25's late-sent order, which T-137
				// deliberately left in the fill rate. Ordering late excuses OUR timing and not a
				// half-empty lorry; this excuses THEIR shortfall, which is what the fill rate
				// measures. Waiving the black mark and leaving 60% standing in the column that
				// measures the shortfall would waive almost nothing.
				.andExpect(jsonPath("$.vendors[0].onTimePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(0))
				// And it is visible, which is part of the ruling rather than a nicety: "a number
				// whose exclusions are invisible cannot be checked".
				.andExpect(jsonPath("$.vendors[0].ordersExcused").value(1))
				.andExpect(jsonPath("$.ordersExcused").value(1));
	}

	@Test
	@DisplayName("neither: the order is closed, the figures stand, and nothing is claimed about anybody")
	void neitherIsScoredAsComputedAndClaimsNothing() throws Exception {
		String id = judgeable(new BigDecimal("10"), new BigDecimal("6"));

		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(60))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(60))
				.andExpect(jsonPath("$.vendors[0].ordersExcused").value(0));

		JsonNode order = detail(id).get("order");
		assert order.get("closeOutcome").asText().equals("AS_COMPUTED") : order.toString();
		assert order.get("closeNote").isNull() : "nothing was claimed, so nothing had to be explained";
	}

	// ---- The score is shown, and is not a control -----------------------

	@Test
	@DisplayName("the score is on the order at closing time, and is the one the scorecard reports")
	void theComputedScoreIsShownAtClosingTime() throws Exception {
		String id = judgeable(new BigDecimal("10"), new BigDecimal("6"));

		JsonNode score = detail(id).get("deliveryScore");
		assert score.get("percent").asInt() == 60 : score.toString();
		assert score.get("itemsScored").asInt() == 1 : score.toString();
		// Six of ten kilos is not a whole item on time, and the counts beside the percentage are
		// what let a reader see the difference between one item short and ten items a fifth short.
		assert score.get("itemsOnTime").asInt() == 0 : score.toString();

		// The same number the report gives, from the same arithmetic. If these two could disagree,
		// the transparency the ruling asked for would be a second thing to argue about.
		mvc.perform(report()).andExpect(jsonPath("$.vendors[0].onTimePercent").value(60));
	}

	@Test
	@DisplayName("an order with no needed-by date has nothing to score, and says so rather than inventing a figure")
	void noNeededByDateIsNothingToScore() throws Exception {
		String id = partDelivered(new BigDecimal("10"), new BigDecimal("6"), null);

		JsonNode score = detail(id).get("deliveryScore");
		assert score.get("percent").isNull() : score.toString();
		assert score.get("itemsScored").asInt() == 0 : score.toString();
	}

	/**
	 * The claim this test exists for, and it is the one D-26 turns on.
	 *
	 * <p>Rajeev proposed letting the admin move the number — <em>"there is SO MUCH human interaction
	 * that no machine or app can capture"</em> — and then ruled against his own proposal:
	 * <em>"Let us not let the admin adjust the score. Just show it to them."</em>
	 *
	 * <p>So this posts a score at the endpoint, the way T-129's guard is tested by posting the
	 * no-show field straight at the endpoint rather than through a screen. A rule that lives only in
	 * a form is not a rule — the same endpoint takes the same body from anything that can post to
	 * it. The order must close on the outcome it was given and the figures must be exactly what the
	 * receipts made them.
	 */
	@Test
	@DisplayName("posting a score at the endpoint moves nothing: the figure is shown, never adjusted")
	void theScoreIsShownAndThereIsNoWayToMoveIt() throws Exception {
		String id = judgeable(new BigDecimal("10"), new BigDecimal("6"));

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/close", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"outcome":"VENDOR_LET_US_DOWN","note":"Never rang back.",
								 "onTimePercent":100,"score":100,"fillRatePercent":100,
								 "deliveryScore":{"percent":100}}
								"""))
				.andExpect(status().isNoContent());

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(60))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(60));
		assert detail(id).get("deliveryScore").get("percent").asInt() == 60
				: "the figure is computed from receipts and there is nothing that writes it";
	}

	// ---- Anything other than "as computed" requires a sentence ----------

	@Test
	@DisplayName("a named outcome with no sentence is refused, and the refusal names the field")
	void aNamedOutcomeNeedsASentence() throws Exception {
		String id = partDelivered(new BigDecimal("10"), new BigDecimal("6"), today.minusDays(2));

		close(id, "VENDOR_LET_US_DOWN", null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("namedOutcomeExplained"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("Say why, in a sentence. This is going on the vendor's record."));

		// Blank is absent. A space bar is not an explanation, and accepting one would leave a
		// permanent claim on a supplier's record with an empty line where its reason should be.
		close(id, "SHORTFALL_EXCUSED", "   ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		assert statusOf(id).equals("PARTIALLY_RECEIVED") : "a refusal must not half-close the order";

		// And the third outcome claims nothing about anybody, so it needs no sentence.
		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());
		assert statusOf(id).equals("CLOSED");
	}

	@Test
	@DisplayName("an outcome nobody has heard of is refused rather than stored")
	void anUnknownOutcomeIsRefused() throws Exception {
		String id = partDelivered(new BigDecimal("10"), new BigDecimal("6"), today.minusDays(2));

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/close", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"outcome\":\"FORGIVEN_A_BIT\",\"note\":\"x\"}"))
				.andExpect(status().isBadRequest());
		assert statusOf(id).equals("PARTIALLY_RECEIVED");
	}

	// ---- Which orders may be closed, and what closing ends --------------

	@Test
	@DisplayName("only a part-delivered order can be closed — the other endings already have their own doors")
	void onlyAPartDeliveredOrderCanBeClosed() throws Exception {
		// A draft is swept or cancelled; nothing has been asked of anybody.
		String draft = order(new BigDecimal("10"), today.plusDays(3));
		close(draft, "AS_COMPUTED", null).andExpect(status().isConflict());

		// A sent order nothing arrived against is a cancellation, ticked "Vendor Never Delivered
		// this Order" if that is what happened (T-124). There is nothing to close: nothing came and
		// nothing is owed for.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", draft)))
				.andExpect(status().isNoContent());
		close(draft, "AS_COMPUTED", null).andExpect(status().isConflict());

		// And a finished order is finished.
		admin.update("UPDATE purchase_orders SET status = 'RECEIVED' WHERE id = ?::uuid", draft);
		close(draft, "AS_COMPUTED", null).andExpect(status().isConflict());
	}

	@Test
	@DisplayName("a closed order is terminal: it cannot be closed twice, cancelled, or sent to the vendor again")
	void aClosedOrderIsTerminal() throws Exception {
		String id = partDelivered(new BigDecimal("10"), new BigDecimal("6"), today.minusDays(2));
		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());

		close(id, "VENDOR_LET_US_DOWN", "Changed my mind about them.")
				.andExpect(status().isConflict());

		// Cancelling is the dangerous one and the reason this assertion is here. A cancellation
		// takes the order out of the scorecard's LIVE_ORDER predicate entirely, so cancelling a
		// closed order would erase a real 6 kg delivery from this supplier's record — and could tick
		// "Vendor Never Delivered this Order" against a vendor who demonstrably did.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"On reflection\",\"vendorAbandoned\":true}"))
				.andExpect(status().isConflict())
				// PO_INVALID_TRANSITION and not KMS-400150: this order is finished, which is a
				// different statement from "part of it arrived, close it instead". The second would
				// send somebody to a door they have already walked through.
				.andExpect(jsonPath("$.code").value("KMS-400051"));

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/whatsapp", id)))
				.andExpect(status().isConflict());

		assert statusOf(id).equals("CLOSED") : "none of that touched the order";
		assert !Boolean.TRUE.equals(admin.queryForObject(
				"SELECT vendor_abandoned FROM purchase_orders WHERE id = ?::uuid", Boolean.class, id))
				: "a closed order must not be able to acquire a no-show claim";
	}

	/**
	 * The hole T-142 closes, and the reason it is a defect rather than untidiness.
	 *
	 * <p>Cancelling leaves the scorecard's {@code LIVE_ORDER} predicate entirely, so cancelling a
	 * part-delivered order erases from the supplier's record the goods that actually arrived — and
	 * lets somebody tick <em>"Vendor Never Delivered this Order"</em> against a vendor who
	 * demonstrably did deliver. That is a number this product promises is defensible becoming
	 * indefensible, which is the standard D-25, T-129 and {@code KMS-400149} were all built to hold.
	 *
	 * <p><strong>Posted straight at the endpoint, never through the screen.</strong> The screen no
	 * longer offers the control, and that is not what makes this a rule: the same endpoint takes the
	 * same body from anything that can post to it, which is the lesson sitting three lines above this
	 * code in {@code PO_NEVER_SENT_TO_VENDOR}'s comment and the one T-137 was sent back for.
	 */
	@Test
	@DisplayName("a part-delivered order cannot be cancelled at all — closing is the door, and the endpoint says so")
	void aPartDeliveredOrderCannotBeCancelled() throws Exception {
		String id = partDelivered(new BigDecimal("500"), new BigDecimal("300"), today.minusDays(2));

		// Without the tick: still refused, because an unticked cancellation erases the delivery just
		// as thoroughly. The state is what is wrong, not the claim.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Changed our minds\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400150"))
				.andExpect(jsonPath("$.message")
						.value("Part of this order has already arrived, so it can't be cancelled."))
				// `action` is what ErrorResponse calls the next step; every KMS code carries one.
				.andExpect(jsonPath("$.action").value("Close it instead. What arrived stays on the "
						+ "vendor's record, and what didn't goes back on the shopping list."));

		// And with it, which is the case that was actually dangerous.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Never came\",\"vendorAbandoned\":true}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400150"));

		assert statusOf(id).equals("PARTIALLY_RECEIVED") : "a refusal must not half-cancel the order";
		assert !Boolean.TRUE.equals(admin.queryForObject(
				"SELECT vendor_abandoned FROM purchase_orders WHERE id = ?::uuid", Boolean.class, id))
				: "a supplier who delivered 300 kg must not be able to acquire a no-show claim";

		// The door that IS right is open, and the same order goes through it.
		close(id, "VENDOR_LET_US_DOWN", "Never rang back about the rest.")
				.andExpect(status().isNoContent());
		assert statusOf(id).equals("CLOSED");
	}

	/** A draft and a sent order are unaffected: cancelling those is still the right ending. */
	@Test
	@DisplayName("cancelling a draft and a sent order still works — only the part-delivered case moved")
	void cancellingWhatWasNeverDeliveredStillWorks() throws Exception {
		String draft = order(new BigDecimal("10"), today.plusDays(3));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", draft))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ordered from the wrong merchant\"}"))
				.andExpect(status().isNoContent());

		String sent = order(new BigDecimal("10"), today.plusDays(3));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", sent)))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", sent))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Nothing ever came\",\"vendorAbandoned\":true}"))
				.andExpect(status().isNoContent());
	}

	/**
	 * The audit row, and why it is the waiver that makes it necessary.
	 *
	 * <p>Excusing a shortfall is the single act an admin can take that changes what a supplier's
	 * scorecard reports. D-26's third cost is that such a figure has to be defensible — <em>"an admin
	 * changed it" is not an answer to a vendor who disputes their score</em> — and this row is what
	 * answers instead: who, when, and in what words.
	 */
	@Test
	@DisplayName("closing is on the audit record, with the outcome and the sentence in its after-state")
	void closingIsAudited() throws Exception {
		String id = partDelivered(new BigDecimal("500"), new BigDecimal("300"), today.minusDays(2));
		close(id, "SHORTFALL_EXCUSED", "Rang the same afternoon, 10% off the next load.")
				.andExpect(status().isNoContent());

		Map<String, Object> row = admin.queryForMap("""
				SELECT action, entity_type, entity_id, actor_user_id, actor_label, before_state,
					   after_state, reason
				FROM audit_events WHERE entity_id = ?::uuid AND action = 'PO_CLOSED'
				""", id);
		assert row.get("entity_type").equals("PURCHASE_ORDER") : row.toString();
		// A person did this, and the record names them. That is the whole difference from T-137's
		// sweep, which leaves an actorless line on the order's trail because no person was there.
		assert staffId.equals(row.get("actor_user_id")) : row.toString();
		assert String.valueOf(row.get("actor_label")).contains("Staff A") : row.toString();
		String after = String.valueOf(row.get("after_state"));
		assert after.contains("SHORTFALL_EXCUSED") : after;
		assert after.contains("10% off the next load") : after;
		assert String.valueOf(row.get("before_state")).contains("PARTIALLY_RECEIVED")
				: row.get("before_state").toString();
		assert String.valueOf(row.get("reason")).startsWith("Rang the same afternoon") : row.toString();
	}

	@Test
	@DisplayName("a closed order is still invoiceable — 300 kg of rice arrived and is owed for")
	void aClosedOrderCanStillBeBilled() throws Exception {
		String id = partDelivered(new BigDecimal("500"), new BigDecimal("300"), today.minusDays(2));
		close(id, "AS_COMPUTED", null).andExpect(status().isNoContent());

		// The invoice screen's own list (T-082). Closing settles what the VENDOR still owes us, not
		// what we owe them, and this is the query that would most easily have got that backwards
		// when a new status appeared.
		mvc.perform(authed(get("/api/v1/purchase-orders")
						.param("vendorId", vendor.toString())
						.param("openOnly", "true")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.status=='CLOSED')]").exists());
	}

	/**
	 * D-26's one standing rule, agreed by Rajeev explicitly, asserted from this side as well.
	 *
	 * <p>T-137's sweep matches {@code status = 'DRAFT'} only and its own suite proves that
	 * ({@code theSweepLeavesAPartDeliveredOrderAlone}). It is asserted again here because T-142 is
	 * the first thing that makes part-delivered and closed orders common, and because a guard
	 * tested only in the suite that wrote it is a guard the next person deletes.
	 */
	@Test
	@DisplayName("the nightly sweep never touches a part-delivered or a closed order, however old")
	void theSweepLeavesThesePartsAlone() throws Exception {
		String live = partDelivered(new BigDecimal("10"), new BigDecimal("6"), today.minusDays(30));
		String closed = partDelivered(new BigDecimal("10"), new BigDecimal("6"), today.minusDays(30));
		close(closed, "AS_COMPUTED", null).andExpect(status().isNoContent());

		assert autoCancel.sweep() == 0 : "a sweep must not decide this";
		assert statusOf(live).equals("PARTIALLY_RECEIVED");
		assert statusOf(closed).equals("CLOSED");
	}

	// ---------------------------------------------------------------------

	/** One line, one vendor, one date — through the endpoint, because the lifecycle is the API's. */
	private String order(BigDecimal quantity, LocalDate neededBy) throws Exception {
		String body = "{\"vendorId\":\"" + vendor + "\","
				+ "\"neededBy\":" + (neededBy == null ? "null" : "\"" + neededBy + "\"") + ","
				+ "\"lines\":[{\"ingredientId\":\"" + rice + "\",\"quantity\":" + quantity
				+ ",\"unit\":\"KG\"}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	/**
	 * An order sent and part delivered — the state D-26 is entirely about.
	 *
	 * <p>The receipt and the status are written straight to the columns rather than driven through
	 * {@code ReceivingService}. That path is exercised end to end in {@code ReceivingIT}, including
	 * the release this task changes; what is being tested here is how the stored state is read and
	 * ended, and going through receiving would drag batches, stock movements and expiry dates into
	 * every fixture for no assertion. It is the same reasoning {@code VendorPerformanceIT} and
	 * {@code PurchaseOrderLeadTimeIT} write their fixtures under.
	 */
	private String partDelivered(BigDecimal ordered, BigDecimal received, LocalDate neededBy)
			throws Exception {
		String id = order(ordered, neededBy == null || !neededBy.isBefore(today)
				? neededBy : today.plusDays(1));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
		if (neededBy != null && neededBy.isBefore(today)) {
			admin.update("UPDATE purchase_orders SET needed_by = ? WHERE id = ?::uuid", neededBy, id);
		}
		receive(id, received, neededBy == null ? today : neededBy);
		admin.update("UPDATE purchase_orders SET status = 'PARTIALLY_RECEIVED' WHERE id = ?::uuid", id);
		return id;
	}

	/**
	 * The same, moved far enough into the past that the scorecard has something to say about it.
	 *
	 * <p>An order is judged once its needed-by date has strictly passed, and an order can only be
	 * raised with today's order date (KMS-400014) — so the dates go in through the columns, which is
	 * what {@code PurchaseOrderLeadTimeIT.backdate} exists for and for the same reason.
	 */
	private String judgeable(BigDecimal ordered, BigDecimal received) throws Exception {
		String id = partDelivered(ordered, received, today.minusDays(5));
		admin.update("UPDATE purchase_orders SET order_date = ? WHERE id = ?::uuid",
				today.minusDays(10), id);
		return id;
	}

	/** A delivery booked against every line on the order, dated in the temple's own zone. */
	private void receive(String id, BigDecimal quantity, LocalDate on) {
		UUID receipt = admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, received_at)
				VALUES (?, ?::uuid, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, id, UUID.randomUUID().toString(), staffId,
				on.atTime(12, 0).atZone(TEMPLE_ZONE).toOffsetDateTime());
		admin.query("SELECT id, ingredient_id, unit FROM purchase_order_lines WHERE po_id = ?::uuid",
				rs -> {
					admin.update("""
							INSERT INTO goods_receipt_lines (
								tenant_id, receipt_id, po_line_id, ingredient_id, received_qty,
								rejected_qty, unit)
							VALUES (?, ?, ?, ?, ?, 0, ?)
							""", tenant, receipt, rs.getObject("id", UUID.class),
							rs.getObject("ingredient_id", UUID.class), quantity, rs.getString("unit"));
				}, id);
	}

	private org.springframework.test.web.servlet.ResultActions close(
			String id, String outcome, String note) throws Exception {
		String body = "{\"outcome\":\"" + outcome + "\",\"note\":"
				+ (note == null ? "null" : JSON.writeValueAsString(note)) + "}";
		return mvc.perform(authed(post("/api/v1/purchase-orders/{id}/close", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private MockHttpServletRequestBuilder report() {
		return authed(get("/api/v1/vendor-performance")
				.param("from", today.minusDays(60).toString())
				.param("to", today.toString()));
	}

	private JsonNode detail(String id) throws Exception {
		return JSON.readTree(mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	private JsonNode shoppingListLine(String ingredientName) throws Exception {
		JsonNode list = JSON.readTree(mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		for (JsonNode line : list) {
			if (line.get("ingredientName").asText().equals(ingredientName)) {
				return line;
			}
		}
		return null;
	}

	private boolean shoppingListHas(String ingredientName) throws Exception {
		return shoppingListLine(ingredientName) != null;
	}

	private String statusOf(String id) {
		return admin.queryForObject(
				"SELECT status FROM purchase_orders WHERE id = ?::uuid", String.class, id);
	}

	private String poNumber(String id) {
		return admin.queryForObject(
				"SELECT po_number FROM purchase_orders WHERE id = ?::uuid", String.class, id);
	}

	private String trail(String id, String eventType) {
		return admin.queryForObject("""
				SELECT detail FROM po_events WHERE po_id = ?::uuid AND event_type = ?
				""", String.class, id, eventType);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
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
