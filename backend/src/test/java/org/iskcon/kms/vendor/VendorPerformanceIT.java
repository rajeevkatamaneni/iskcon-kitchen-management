package org.iskcon.kms.vendor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The vendor performance report (E5-S9), against real purchase orders and real receipts.
 *
 * <p>What these guard is every judgement the report makes about what may be held against a
 * supplier: that a draft never can, that an order still inside its needed-by date is not yet judged,
 * that an order due and never delivered is, that too few orders are marked rather than ranked, and
 * that a dropped vendor keeps their history.
 *
 * <p>And, since T-103, what happens to the fill rate when goods are sent back after they were taken
 * into stock: the vendor's own failures come off it, the temple's own change of mind does not, and a
 * partial return takes off exactly what went back.
 *
 * <p><strong>And, since T-124, the whole of how on-time is scored.</strong> The five tests named
 * {@code rajeevsCase*} are Rajeev's own delivery scenarios of 2026-09-09, in his order, each
 * asserting the figure he gave for it. They are the specification and they are meant to be read as
 * one block: all-on-time is 100%, split across days inside the window is 100%, eight of ten items
 * with two late is 80%, nothing inside the window is 0%, and nothing ever delivered is 0% and named
 * as a no-show. Four of the five scored wrongly before this task.
 */
@AutoConfigureMockMvc
@Import(VendorPerformanceIT.StubVerifierConfiguration.class)
class VendorPerformanceIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private LocalDate today;
	private int poCounter;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		today = LocalDate.now(TEMPLE_ZONE);
		poCounter = 0;
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM goods_returns");
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_status_changes");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("on-time is a percentage with the counts behind it — 4 of 5, and the vendor is ranked")
	void onTimeCarriesItsDenominator() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		for (int i = 0; i < 4; i++) {
			UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
			fullyReceived(po, days(-11));
		}
		UUID late = order(vendor, days(-20), days(-10), "RECEIVED");
		fullyReceived(late, days(-4));

		mvc.perform(report())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(5))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(4))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(80))
				.andExpect(jsonPath("$.vendors[0].enoughToRank").value(true))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(100));
	}

	@Test
	@DisplayName("a quarter of the order on the day is a quarter on time, not a punctual delivery")
	void aPartDeliveryScoresThePartThatCame() throws Exception {
		// CHANGED ON PURPOSE AT T-124, and this is the test that says what changed.
		//
		// This used to assert 100% on-time beside 25% fill, and the class comment argued for it at
		// length: on-time asked whether the lorry turned up and fill was what caught a short load.
		// The pair does not work, because fill is silent about WHEN. Ten kilos of forty on the day
		// and the rest whenever scored a hundred per cent punctual, and nothing on the screen said
		// the kitchen had cooked without thirty kilos of rice.
		//
		// The fill rate is deliberately still 25% and deliberately still a different figure: it
		// counts what the temple kept, whenever it arrived, and here nothing else ever did.
		UUID vendor = vendor("Half Load Traders");
		for (int i = 0; i < 5; i++) {
			UUID po = order(vendor, days(-20), days(-10), "PARTIALLY_RECEIVED");
			UUID line = line(po, "40");
			// Ten of the forty kilos, on the day it was wanted.
			receiptLine(receipt(po, days(-10)), line, "10", "0", null);
		}

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(25))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(25))
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(5))
				// Five items scored, none of them fully there — which is the sentence the screen
				// prints beside the percentage, and the one that separates "a fifth of everything"
				// from "four of five things".
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(5))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(0));
	}

	@Test
	@DisplayName("an order due and never delivered is late, not merely absent")
	void nothingDeliveredCountsAsLate() throws Exception {
		UUID vendor = vendor("Silent Supplies");
		for (int i = 0; i < 5; i++) {
			line(order(vendor, days(-25), days(-20), "SENT"), "40");
		}

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(5))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(0))
				.andExpect(jsonPath("$.vendors[0].openOrders").value(5))
				.andExpect(jsonPath("$.vendors[0].openDue1To30").value(5));
	}

	@Test
	@DisplayName("an order we sent after their lead time is counted as placed and judged nowhere")
	void anOrderWeSentLateIsExcludedAndShown() throws Exception {
		// T-137, D-25. Rajeev: "That is a FAVOR we are asking." We submitted this order after the
		// notice period this vendor agreed at onboarding, so nothing that happened to it afterwards
		// is theirs to answer for — not a late delivery and not a missing one.
		UUID vendor = vendor("Govind Wholesale");
		for (int i = 0; i < 4; i++) {
			UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
			fullyReceived(po, days(-11));
		}
		// The fifth was ours to get wrong: nothing was ever delivered, which on any ordinary order
		// would drag this vendor from 100% to 80%.
		UUID late = sentLate(order(vendor, days(-20), days(-10), "SENT"));
		line(late, "40");

		mvc.perform(report())
				.andExpect(status().isOk())
				// Placed with them — we did order it, and the count says so.
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(5))
				// Judged on the four we gave them a fair chance at.
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(4))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				// And the exclusion is on the screen, which is the half of the ruling that makes the
				// percentage checkable: "a reader who cannot see what was excluded cannot check the
				// number".
				.andExpect(jsonPath("$.vendors[0].ordersSentLate").value(1))
				.andExpect(jsonPath("$.ordersSentLate").value(1));
	}

	@Test
	@DisplayName("a late-sent order marked as a no-show still counts against nobody")
	void aLateSentNoShowIsStillNotTheirs() throws Exception {
		// The tick box is not offered on such an order (T-129 as D-25 extends it), so this row can
		// only arrive from outside the screen — and if it does, it must still carry no weight. The
		// order is excluded before the abandoned tick is even looked at.
		UUID vendor = vendor("Silent Supplies");
		UUID po = sentLate(abandoned(vendor, days(-20), days(-10)));
		line(po, "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(0))
				.andExpect(jsonPath("$.vendors[0].abandonedOrders").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].ordersSentLate").value(1));
	}

	@Test
	@DisplayName("a draft, and a cancellation nobody blamed the vendor for, are never held against them")
	void draftsAndOrdinaryCancellationsAreOut() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		UUID received = order(vendor, days(-20), days(-10), "RECEIVED");
		fullyReceived(received, days(-11));
		line(order(vendor, days(-20), days(-10), "DRAFT"), "40");
		line(cancelled(vendor, days(-20), days(-10)), "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].abandonedOrders").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].openOrders").value(0));
	}

	@Test
	@DisplayName("an order still inside its needed-by date is open, not yet judged")
	void anOrderWithTimeLeftIsNotJudged() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		line(order(vendor, days(-2), days(5), "SENT"), "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].openCurrent").value(1));
	}

	@Test
	@DisplayName("an order with no needed-by date is counted aside, never scored a silent hundred")
	void noNeededByIsCountedAside() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		line(order(vendor, days(-20), null, "SENT"), "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersWithoutNeededBy").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].openCurrent").value(1));
	}

	@Test
	@DisplayName("open orders age into the payables buckets, whenever they were placed")
	void openOrdersAgeIntoThePayablesBuckets() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		line(order(vendor, days(-300), days(-200), "SENT"), "40");
		line(order(vendor, days(-20), days(-10), "PARTIALLY_RECEIVED"), "40");
		line(order(vendor, days(-2), days(5), "SENT"), "40");

		// The period covers only the last four weeks; the order placed 300 days ago is outside it and
		// still has to appear, because a supplier sitting on an order since last year is the finding.
		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].openOrders").value(3))
				.andExpect(jsonPath("$.vendors[0].openOverdue31Plus").value(1))
				.andExpect(jsonPath("$.vendors[0].openDue1To30").value(1))
				.andExpect(jsonPath("$.vendors[0].openCurrent").value(1));
	}

	@Test
	@DisplayName("rejections are counted by reason, commonest first")
	void rejectionsAreGroupedByReason() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "PARTIALLY_RECEIVED");
		UUID receipt = receipt(po, days(-10));
		receiptLine(receipt, line(po, "40"), "30", "10", "SPOILED");
		receiptLine(receipt, line(po, "20"), "15", "5", "SPOILED");
		receiptLine(receipt, line(po, "10"), "8", "2", "DAMAGED");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].rejectedLines").value(3))
				.andExpect(jsonPath("$.vendors[0].rejections[0].reason").value("SPOILED"))
				.andExpect(jsonPath("$.vendors[0].rejections[0].lines").value(2))
				.andExpect(jsonPath("$.vendors[0].rejections[1].reason").value("DAMAGED"))
				.andExpect(jsonPath("$.vendors[0].rejections[1].lines").value(1));
	}

	@Test
	@DisplayName("too few orders to rank: shown with its figures, below the ranked ones, and marked")
	void tooFewOrdersIsMarkedNotHidden() throws Exception {
		UUID ranked = vendor("Govind Wholesale");
		for (int i = 0; i < 5; i++) {
			fullyReceived(order(ranked, days(-20), days(-10), "RECEIVED"), days(-4));
		}
		UUID scarce = vendor("Amba Traders");
		UUID one = order(scarce, days(-20), days(-10), "RECEIVED");
		fullyReceived(one, days(-11));

		// Govind is on time 0% and Amba 100%, so worst-first would put Govind top anyway — but Amba
		// is second here because one order does not earn a place in the ranking at all.
		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$.vendors[0].enoughToRank").value(true))
				.andExpect(jsonPath("$.vendors[1].vendorName").value("Amba Traders"))
				.andExpect(jsonPath("$.vendors[1].enoughToRank").value(false))
				.andExpect(jsonPath("$.vendors[1].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[1].ordersJudged").value(1));
	}

	@Test
	@DisplayName("a dropped vendor keeps their history on the report, marked as no longer used")
	void aDeactivatedVendorStaysOnTheReport() throws Exception {
		UUID vendor = vendor("Dropped Traders");
		fullyReceived(order(vendor, days(-20), days(-10), "RECEIVED"), days(-4));
		admin.update("UPDATE vendors SET active = false WHERE id = ?", vendor);

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Dropped Traders"))
				.andExpect(jsonPath("$.vendors[0].active").value(false))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0));
	}

	@Test
	@DisplayName("a vendor with nothing ordered and nothing open is not a row of dashes")
	void aVendorWithNoActivityIsAbsent() throws Exception {
		vendor("Never Used Traders");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors").isEmpty());
	}

	@Test
	@DisplayName("the report totals every vendor's judged orders together")
	void totalsAddUpAcrossVendors() throws Exception {
		UUID a = vendor("Govind Wholesale");
		fullyReceived(order(a, days(-20), days(-10), "RECEIVED"), days(-11));
		UUID b = vendor("Amba Traders");
		fullyReceived(order(b, days(-20), days(-10), "RECEIVED"), days(-4));

		mvc.perform(report())
				.andExpect(jsonPath("$.ordersJudged").value(2))
				.andExpect(jsonPath("$.onTimeOrders").value(1))
				.andExpect(jsonPath("$.onTimePercent").value(50));
	}

	@Test
	@DisplayName("a described line leaves the fill rate exactly where it would have been without it")
	void aDescribedLineDoesNotDragTheFillRateDown() throws Exception {
		// The same order, twice, from two vendors — thirty of the forty kilos delivered on the day.
		// Amba's is that line and nothing else. Stool's carries four plastic stools beside it: a line
		// the temple can order and pay for and can never receive (T-024, KMS-400129). The two fill
		// rates have to be the same number, because nothing about how Stool delivered rice differs.
		UUID control = vendor("Amba Traders");
		UUID controlPo = order(control, days(-20), days(-10), "PARTIALLY_RECEIVED");
		receiptLine(receipt(controlPo, days(-10)), line(controlPo, "40"), "30", "0", null);

		UUID stools = vendor("Stool Traders");
		UUID stoolPo = order(stools, days(-20), days(-10), "PARTIALLY_RECEIVED");
		receiptLine(receipt(stoolPo, days(-10)), line(stoolPo, "40"), "30", "0", null);
		describedLine(stoolPo, "4", "Plastic stool");

		// Counting the stools would make it 0.75 over two lines — 38% — and no delivery of rice
		// would ever bring it back, because a described line's accepted quantity is zero for ever.
		//
		// The row ORDER changed at T-124 and the fill rates did not, which is the thing to notice.
		// Stool Traders now leads because the report sorts worst on-time first and the stools nobody
		// has acknowledged score them nothing on that column — 38% on-time against Amba's 75%. Their
		// FILL rates are still identical, because nothing about how either of them delivered rice
		// differs. That is the ruling this test was written for and it is untouched.
		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Stool Traders"))
				// The fill rate first, deliberately: it is the figure the vendor is judged on, so it
				// should be the figure that fails first if this ever regresses.
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(75))
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(38))
				.andExpect(jsonPath("$.vendors[1].vendorName").value("Amba Traders"))
				.andExpect(jsonPath("$.vendors[1].fillRatePercent").value(75))
				.andExpect(jsonPath("$.vendors[1].linesJudged").value(1))
				.andExpect(jsonPath("$.vendors[1].onTimePercent").value(75))
				// And the same again in the totals row, which averages over judged lines.
				.andExpect(jsonPath("$.fillRatePercent").value(75))
				.andExpect(jsonPath("$.linesJudged").value(2));
	}

	@Test
	@DisplayName("an order of nothing but described lines leaves the fill rate blank, and scores late until somebody says they arrived")
	void anOrderOfOnlyDescribedLinesIsNotJudgedOnFill() throws Exception {
		UUID vendor = vendor("Stool Traders");
		describedLine(order(vendor, days(-20), days(-10), "SENT"), "4", "Plastic stool");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Stool Traders"))
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				// Blank, not 0% — the same ruling the report already makes for an order with no
				// needed-by date. There is no line here that the store room could have taken in, so
				// there is no fraction of it that arrived, and a percentage would be an invention.
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(0))
				// CHANGED ON PURPOSE AT T-066 — the numbers are the same and they now mean the
				// opposite thing, which is exactly why this comment is being rewritten rather than
				// left alone.
				//
				// T-060 pinned these two assertions with a note saying they were pinned and NOT
				// endorsed: on-time was measured per order at its first goods receipt, an order of
				// only described lines could never have one, and so it read as late for ever. That
				// was a judgement about our schema wearing the clothes of a judgement about a
				// supplier, and there was no action anywhere in the application that could have
				// changed it.
				//
				// T-066 built that action, and Rajeev's ruling 4 explicitly REJECTED the
				// alternative of excusing such orders from on-time judgement — because a vendor who
				// genuinely never delivered the stools would then score nothing at all,
				// indistinguishable from one who delivered them on the day. Nobody has recorded
				// that these stools arrived. So as far as this report knows they did not, and a
				// zero is now the honest answer rather than an unavoidable one. It is endorsed.
				//
				// The sibling test below is the other half: record the arrival and it scores 100.
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0));
	}

	@Test
	@DisplayName("an order of only described lines is judged on-time against the day they were recorded as arriving")
	void anOrderOfOnlyDescribedLinesIsJudgedOnItsArrival() throws Exception {
		// Two vendors, the same order, one difference: when somebody said the stools turned up.
		// Before T-066 both of these scored 0% and there was no way to tell them apart, which is
		// the defect — the report could not distinguish a supplier who delivered from one who
		// never did.
		UUID punctual = vendor("Amba Traders");
		UUID punctualLine = describedLine(order(punctual, days(-20), days(-10), "RECEIVED"), "4", "Plastic stool");
		arrived(punctualLine, days(-10));

		UUID late = vendor("Stool Traders");
		UUID lateLine = describedLine(order(late, days(-20), days(-10), "RECEIVED"), "4", "Plastic stool");
		arrived(lateLine, days(-3));

		mvc.perform(report())
				// Worst on-time first, so the late one leads. Both are judged: an acknowledgement
				// gives on-time something to measure where a goods receipt never could.
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Stool Traders"))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0))
				.andExpect(jsonPath("$.vendors[1].vendorName").value("Amba Traders"))
				.andExpect(jsonPath("$.vendors[1].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[1].onTimeOrders").value(1))
				.andExpect(jsonPath("$.vendors[1].onTimePercent").value(100))
				// And the fill rate stays blank for both. T-060's ruling is untouched: a described
				// line is not judged on fill, because there is no quantity the store room could
				// have taken in and no fraction of it that could have turned up.
				.andExpect(jsonPath("$.vendors[1].fillRatePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[1].linesJudged").value(0));
	}

	@Test
	@DisplayName("a mixed order counts the rice and the stools once each, and the late half halves it")
	void aDescribedLineCountsAsOneItemBesideTheRice() throws Exception {
		// CHANGED ON PURPOSE AT T-124. This asserted 100% and was named for the rule it proved —
		// on-time read the EARLIER of a goods receipt and an acknowledgement, so the rice lorry
		// making the day covered stools confirmed a week later.
		//
		// Now each item is counted once. The rice was all there in time and scores one; the stools
		// were not and score nothing; the order is the mean of the two. A described line has no
		// quantity anybody can weigh — nobody records that two of the four stools came — so it is
		// yes or no off arrived_on, and it is a full item on the order either way.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		receiptLine(receipt(po, days(-10)), line(po, "40"), "40", "0", null);
		arrived(describedLine(po, "4", "Plastic stool"), days(-3));

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(50))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(2))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(1))
				// And the fill rate is untouched by the stools, exactly as T-024 ruled: one judged
				// line, fully delivered. The two figures now disagree, which is the whole point.
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(100));
	}

	@Test
	@DisplayName("a delivery sent back for weevils is not a delivery, and the vendor is still on time")
	void aFullyReturnedDeliveryIsNotFilled() throws Exception {
		// The case the ruling was made on: fifty kilos of rice arrived on the day, the sacks were
		// opened the next morning and all fifty went back. Before T-103 this vendor's fill rate read
		// 100% — identical to one whose rice was fine — because a return never touches received_qty.
		UUID vendor = vendor("Weevil Traders");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID poLine = line(po, "50");
		returned(receiptLine(receipt(po, days(-10)), poLine, "50", "0", null), "50", "SPOILED");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Weevil Traders"))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(0))
				// The line is still judged. Nothing was filled; that is not the same as there being
				// nothing to fill, which is what a blank cell would say (T-024's ruling).
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(1))
				// And on-time is untouched, deliberately (T-103). The lorry came on the day; the
				// goods went back a fortnight later. The fill rate beside it is what says they did.
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100));
	}

	@Test
	@DisplayName("a partial return takes off what went back and not one kilo more")
	void aPartialReturnSubtractsOnlyTheQuantityReturned() throws Exception {
		// Forty-five of the fifty back is the common case, not all-or-nothing, and the five that
		// stayed fed somebody. Five of fifty ordered is a tenth.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		returned(receiptLine(receipt(po, days(-10)), line(po, "50"), "50", "0", null), "45", "DAMAGED");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(10))
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(1));
	}

	@Test
	@DisplayName("a return the vendor is not to blame for leaves the score exactly where it was")
	void aReturnForOtherDoesNotCountAgainstTheVendor() throws Exception {
		// The assertion that proves the ruling rather than a blanket subtraction (T-103, 2026-09-10).
		// Two vendors, the same delivery, the same quantity back — and one difference, the reason.
		// Amba's rice was fine and the temple had over-ordered, which is the temple's own doing;
		// Govind's was infested. OTHER is the one reason of the five that is not held against the
		// supplier, because the note beside it is usually about us.
		UUID ours = vendor("Amba Traders");
		UUID ourPo = order(ours, days(-20), days(-10), "RECEIVED");
		returned(receiptLine(receipt(ourPo, days(-10)), line(ourPo, "50"), "50", "0", null), "50", "OTHER");

		UUID theirs = vendor("Govind Wholesale");
		UUID theirPo = order(theirs, days(-20), days(-10), "RECEIVED");
		returned(receiptLine(receipt(theirPo, days(-10)), line(theirPo, "50"), "50", "0", null), "50", "SPOILED");

		// Both delivered on the day and neither has the orders to be ranked, so the report falls
		// through to sorting them by name: Amba first, Govind second. The fill rate is the only
		// thing that differs between the two rows, which is the whole point of the pair.
		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Amba Traders"))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(100))
				.andExpect(jsonPath("$.vendors[1].vendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$.vendors[1].fillRatePercent").value(0))
				// Fifty kilos went back on each, so the totals row averages the two: not a figure
				// worth asserting for its own sake, but it is what a temple admin reads first, and
				// it must not be the average of two subtractions.
				.andExpect(jsonPath("$.fillRatePercent").value(50))
				.andExpect(jsonPath("$.linesJudged").value(2));
	}

	@Test
	@DisplayName("more returned than was ever received floors the line at nothing filled, never below")
	void aFillRateCannotGoNegative() throws Exception {
		// GoodsReturnService caps cumulative returns at the receipt line's received_qty
		// (KMS-400140), so these two rows should not be able to exist; they are written straight to
		// the table, past that cap, because a report is the wrong place to find out otherwise. A
		// line that went negative would drag down this vendor's OTHER deliveries through the
		// average — a figure nobody could reconcile against the counts printed beside it.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID receiptLine = receiptLine(receipt(po, days(-10)), line(po, "40"), "40", "0", null);
		returned(receiptLine, "30", "SPOILED");
		returned(receiptLine, "20", "WRONG_ITEM");

		// Fifty back against forty received is minus ten kept, which is minus 25% unclamped.
		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(0))
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(1));
	}

	// ---------------------------------------------------------------------
	// Rajeev's five delivery scenarios, 2026-09-09 (T-124). Each asserts the figure he gave for it.
	// Four of the five scored wrongly before this task; the fifth was not counted at all.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("Rajeev's case A — all ten items inside the three days is 100%")
	void rajeevsCaseA_everythingInsideTheWindow() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID receipt = receipt(po, days(-12));
		for (int i = 0; i < 10; i++) {
			receiptLine(receipt, line(po, "40"), "40", "0", null);
		}

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(10))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(10))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(1));
	}

	@Test
	@DisplayName("Rajeev's case B — some on day 2 and the rest on day 3, all inside the window, is 100%")
	void rajeevsCaseB_splitAcrossDaysInsideTheWindow() throws Exception {
		// The case the old rule got right for the wrong reason: it read the FIRST arrival and
		// stopped. This scores the sum of what was there in time, so two lorries inside the window
		// are the same answer as one — which is what makes case C's answer different.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID line = line(po, "40");
		receiptLine(receipt(po, days(-12)), line, "25", "0", null);
		receiptLine(receipt(po, days(-11)), line, "15", "0", null);

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(1))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(100));
	}

	@Test
	@DisplayName("Rajeev's case C — eight items inside the window and two on day 5 is 80%")
	void rajeevsCaseC_eightOfTenItemsInTheWindow() throws Exception {
		// The scenario the whole task exists for. Under the old rule this scored 100% on-time AND
		// 100% fill: everything arrived in the end, and one sack on the day made the order punctual.
		// Nothing on the screen said the kitchen had cooked without two of its ten ingredients.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID inTime = receipt(po, days(-12));
		for (int i = 0; i < 8; i++) {
			receiptLine(inTime, line(po, "40"), "40", "0", null);
		}
		UUID late = receipt(po, days(-5));
		for (int i = 0; i < 2; i++) {
			receiptLine(late, line(po, "40"), "40", "0", null);
		}

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(80))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(10))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(8))
				// Not a fully on-time order, so it does not count towards that figure — while it
				// does move the percentage. The two are different questions and both are on screen.
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(0))
				// And the fill rate is 100%, unchanged and correct: everything did turn up in the
				// end, and everything was kept. That is exactly why it could not catch this.
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(100));
	}

	@Test
	@DisplayName("Rajeev's case D — nothing inside the window and everything after it is 0%")
	void rajeevsCaseD_nothingInsideTheWindow() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID late = receipt(po, days(-5));
		for (int i = 0; i < 10; i++) {
			receiptLine(late, line(po, "40"), "40", "0", null);
		}

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(10))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(0))
				// Everything arrived, a week late. The fill rate says so and says nothing about the
				// week, which is the division of labour between the two figures.
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(100));
	}

	@Test
	@DisplayName("Rajeev's case E — nothing ever came, we cancelled and went elsewhere: 0%, and named")
	void rajeevsCaseE_neverDeliveredIsNamedAsANoShow() throws Exception {
		// The case that was invisible. Every cancellation was excluded from this report on the
		// perfectly good ground that a cancellation is usually the temple's own decision — with the
		// result that the vendor who never turned up scored nothing at all while the one who was
		// merely late scored badly.
		//
		// Needed-by is deliberately in the FUTURE. Ticking the box is itself the claim that the
		// vendor is not coming, so the order is judged the moment it is cancelled and does not wait
		// for a date nobody is going to meet.
		UUID vendor = vendor("Silent Supplies");
		line(abandoned(vendor, days(-20), days(5)), "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Silent Supplies"))
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].abandonedOrders").value(1))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimeOrders").value(0))
				// The item is counted so that the line beside the percentage still adds up: one
				// item ordered, none of it there.
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(1))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(0))
				// And the fill rate does not move. An abandoned order entering it would drag it to
				// zero and quietly change what an existing figure on an existing screen means.
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").doesNotExist())
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(0));
	}

	// ---------------------------------------------------------------------

	@Test
	@DisplayName("a cancellation without the tick is counted nowhere at all, in either direction")
	void anUntickedCancellationScoresNothingEitherWay() throws Exception {
		// The other half of case E, and the reason the box starts unticked. A temple that cancels
		// for its own reasons — the festival moved, a cheaper source turned up — makes no statement
		// about the supplier, and the report must not manufacture one in either direction. Not a
		// zero, which would blame them; not a hundred, which would credit them; nothing.
		UUID blamed = vendor("Silent Supplies");
		line(abandoned(blamed, days(-20), days(-10)), "40");

		UUID ourOwnDoing = vendor("Amba Traders");
		line(cancelled(ourOwnDoing, days(-20), days(-10)), "40");

		mvc.perform(report())
				// One row, not two. Amba has no orders the report can see and no open orders, so
				// they are absent altogether rather than present as a line of dashes.
				.andExpect(jsonPath("$.vendors.length()").value(1))
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Silent Supplies"))
				.andExpect(jsonPath("$.ordersJudged").value(1))
				.andExpect(jsonPath("$.abandonedOrders").value(1));
	}

	@Test
	@DisplayName("bringing more than was ordered is not a bonus: a line is capped at fully delivered")
	void overDeliveryIsClampedAtAHundred() throws Exception {
		// Twelve sacks where ten were ordered, and one line that never came. If over-delivery paid
		// for a shortfall elsewhere this order would score 100%; it scores half, because each item
		// answers only for itself.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		receiptLine(receipt(po, days(-12)), line(po, "40"), "48", "0", null);
		line(po, "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(50))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(2))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(1));
	}

	@Test
	@DisplayName("a described line is judged yes or no against the day it was recorded as arriving")
	void aDescribedLineIsScoredYesOrNo() throws Exception {
		// Two vendors, the same four stools, one difference: the day somebody said they turned up.
		// There is no quantity to weigh here and no half answer to give — arrived_on is a single
		// date for the whole line by construction — so the scores are one and nothing.
		UUID punctual = vendor("Amba Traders");
		arrived(describedLine(order(punctual, days(-20), days(-10), "RECEIVED"), "4", "Plastic stool"),
				days(-11));

		UUID late = vendor("Stool Traders");
		arrived(describedLine(order(late, days(-20), days(-10), "RECEIVED"), "4", "Plastic stool"),
				days(-9));

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].vendorName").value("Stool Traders"))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(0))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(1))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(0))
				.andExpect(jsonPath("$.vendors[1].vendorName").value("Amba Traders"))
				.andExpect(jsonPath("$.vendors[1].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[1].itemsOnTime").value(1));
	}

	@Test
	@DisplayName("a NOT_DELIVERED return takes its quantity back out of the on-time count")
	void aNeverDeliveredReturnComesOffTheOnTimeCount() throws Exception {
		// This is T-109, dissolved rather than answered (T-124). A NOT_DELIVERED return says the
		// receipt line was a keying error and the goods never existed — fifty keyed where five
		// arrived — so the quantity has to leave the on-time count the same way it leaves the fill
		// rate. No new rule for the partial case: it is the same sum with a wrong number removed.
		//
		// Two lines. One was keyed correctly. The other's fifty kilos never came, and the correction
		// takes all fifty back, so the order scores half rather than a hundred per cent.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		UUID receipt = receipt(po, days(-12));
		receiptLine(receipt, line(po, "50"), "50", "0", null);
		returned(receiptLine(receipt, line(po, "50"), "50", "0", null), "50", "NOT_DELIVERED");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(50))
				.andExpect(jsonPath("$.vendors[0].itemsScored").value(2))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(1));
	}

	@Test
	@DisplayName("a return for weevils leaves on-time alone: the goods were there on the day")
	void aVendorFaultReturnThatIsNotAKeyingErrorLeavesOnTimeAlone() throws Exception {
		// The control for the test above, and the line T-103's ruling drew. Weevils found the next
		// morning are a fact about what the temple could keep, not about whether the lorry came —
		// and a return can be booked six weeks later, so letting it reverse on-time would silently
		// change a figure somebody had already read off the screen.
		UUID vendor = vendor("Weevil Traders");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		returned(receiptLine(receipt(po, days(-12)), line(po, "50"), "50", "0", null), "50", "SPOILED");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].itemsOnTime").value(1))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(0));
	}

	@Test
	@DisplayName("a sack refused at the gate still arrived: on-time counts it, the fill rate does not")
	void rejectedQuantityCountsAsHavingArrived() throws Exception {
		// Kept from the rule this task replaced, and kept deliberately. On-time asks whether the
		// goods were at the gate on the day; a sack refused there was. What became of it afterwards
		// is the fill rate's question and the rejection column's, and folding it in here would
		// collapse two figures into one.
		UUID vendor = vendor("Govind Wholesale");
		UUID po = order(vendor, days(-20), days(-10), "RECEIVED");
		receiptLine(receipt(po, days(-12)), line(po, "40"), "30", "10", "SPOILED");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(75))
				.andExpect(jsonPath("$.vendors[0].rejectedLines").value(1));
	}

	@Test
	@DisplayName("a period whose end falls before its start is refused with KMS-400122")
	void aBackwardsPeriodIsRefused() throws Exception {
		mvc.perform(authed(get("/api/v1/vendor-performance")
						.param("from", today.toString())
						.param("to", today.minusDays(7).toString())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400122"));
	}

	@Test
	@DisplayName("a volunteer cannot read the temple's suppliers")
	void aVolunteerIsRefused() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(report()).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder report() {
		return authed(get("/api/v1/vendor-performance")
				.param("from", today.minusDays(27).toString())
				.param("to", today.toString()));
	}

	private LocalDate days(int delta) {
		return today.plusDays(delta);
	}

	private UUID vendor(String name) {
		return admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id",
				UUID.class, tenant, name);
	}

	private UUID order(UUID vendorId, LocalDate orderDate, LocalDate neededBy, String status) {
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, order_date, needed_by, created_by)
				VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, "PO-2026-" + (++poCounter), vendorId, status, orderDate, neededBy, staffId);
	}

	private UUID cancelled(UUID vendorId, LocalDate orderDate, LocalDate neededBy) {
		return order(vendorId, orderDate, neededBy, "CANCELLED");
	}

	/**
	 * A cancellation somebody ticked "Vendor Never Delivered this Order" on (T-124).
	 *
	 * <p>Written straight to the column, like every other fixture in this file, because the report
	 * is being tested against stored facts rather than against the lifecycle that produced them. The
	 * end-to-end path — the request field, the audit after-state and the order's own trail — is
	 * PurchaseOrderIT.
	 */
	private UUID abandoned(UUID vendorId, LocalDate orderDate, LocalDate neededBy) {
		UUID po = order(vendorId, orderDate, neededBy, "CANCELLED");
		admin.update("UPDATE purchase_orders SET vendor_abandoned = true WHERE id = ?", po);
		return po;
	}

	/**
	 * An order we submitted after this vendor's agreed lead time (T-137, D-25).
	 *
	 * <p>Written straight to the columns, like every other fixture in this file: the report is being
	 * tested against stored facts rather than against the lifecycle that produced them. The verdict
	 * is decided once, in Java, when the order is sent — the end-to-end path, including the refusal
	 * somebody has to override and the stamp that makes it survive an edit to the vendor's profile,
	 * is {@code PurchaseOrderLeadTimeIT}.
	 *
	 * <p>{@code sent_at} comes with it because the schema insists (V125): lateness is a fact about
	 * sending, so it cannot be true of an order nobody sent.
	 */
	private UUID sentLate(UUID poId) {
		admin.update("""
				UPDATE purchase_orders
				SET sent_after_lead_time = true, lead_time_days = 2,
					sent_at = COALESCE(sent_at, now())
				WHERE id = ?
				""", poId);
		return poId;
	}

	private UUID line(UUID poId, String quantity) {
		return admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'KG') RETURNING id
				""", UUID.class, tenant, poId, rice, quantity);
	}

	/**
	 * A line that names something the catalogue has never heard of (T-024): no ingredient, a
	 * description instead, and — by the schema's own CHECK — no way for it ever to be received.
	 */
	private UUID describedLine(UUID poId, String quantity, String description) {
		return admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, description, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'PIECES') RETURNING id
				""", UUID.class, tenant, poId, description, quantity);
	}

	/**
	 * Somebody recorded that a described line's goods turned up on {@code on} (T-066).
	 *
	 * <p>Written straight to the columns rather than through the endpoint, like every other fixture
	 * in this file, because this report is being tested against stored facts and not against the
	 * lifecycle that produced them — and because the endpoint can only ever record the temple's
	 * today, which would leave nothing to be late about. The end-to-end path is
	 * DescribedPurchaseLineIT.
	 */
	private void arrived(UUID poLineId, LocalDate on) {
		admin.update("""
				UPDATE purchase_order_lines
				SET arrived_on = ?, arrived_recorded_at = now(), arrived_recorded_by = ?
				WHERE id = ?
				""", on, staffId, poLineId);
	}

	private UUID receipt(UUID poId, LocalDate receivedOn) {
		return admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, received_at)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, poId, UUID.randomUUID().toString(), staffId,
				receivedOn.atTime(12, 0).atZone(TEMPLE_ZONE).toOffsetDateTime());
	}

	/** Returns the line's id, which the return fixtures below need to point a return at. */
	private UUID receiptLine(UUID receiptId, UUID poLineId, String received, String rejected, String reason) {
		return admin.queryForObject("""
				INSERT INTO goods_receipt_lines (
					tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, rejected_qty,
					reject_reason, unit)
				VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, ?, 'KG') RETURNING id
				""", UUID.class, tenant, receiptId, poLineId, rice, received, rejected, reason);
	}

	/**
	 * Goods sent back to the vendor after they were taken into stock (T-013), written straight to
	 * the two tables the act leaves behind.
	 *
	 * <p>Direct, like every other fixture in this file: the report is being tested against stored
	 * facts rather than against the lifecycle that produced them, and going through
	 * {@code GoodsReturnService} would additionally require the receipt line to carry a batch and
	 * the ledger to hold the receipt that established it. The end-to-end path is
	 * {@code ReturnToVendorIT}. The movement is written because {@code goods_returns.stock_movement_id}
	 * is NOT NULL and says why: a return that moved no stock is a note, not a return.
	 */
	private void returned(UUID receiptLineId, String quantity, String reason) {
		UUID movement = admin.queryForObject("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					reference_type, actor_user_id)
				VALUES (?, ?, gen_random_uuid(), ?::numeric * -1, 'KG', 'RETURN_TO_VENDOR', NULL, ?)
				RETURNING id
				""", UUID.class, tenant, rice, quantity, staffId);
		admin.update("""
				INSERT INTO goods_returns (
					tenant_id, receipt_id, receipt_line_id, idempotency_key, quantity, unit, reason,
					stock_movement_id, returned_by)
				VALUES (?, (SELECT receipt_id FROM goods_receipt_lines WHERE id = ?), ?, ?, ?::numeric,
						'KG', ?, ?, ?)
				""", tenant, receiptLineId, receiptLineId, UUID.randomUUID().toString(), quantity,
				reason, movement, staffId);
	}

	/** One line of forty kilos, ordered and all of it delivered in a single receipt. */
	private void fullyReceived(UUID poId, LocalDate receivedOn) {
		receiptLine(receipt(poId, receivedOn), line(poId, "40"), "40", "0", null);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertUser(String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, uid, email, role);
	}

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
