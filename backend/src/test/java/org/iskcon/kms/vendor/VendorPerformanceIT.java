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
 * supplier: that a draft or a cancellation never can, that a part-delivery on the day counts as
 * on-time and is caught by the fill rate instead, that an order still inside its needed-by date is
 * not yet judged, that an order due and never delivered is, that too few orders are marked rather
 * than ranked, and that a dropped vendor keeps their history.
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
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
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
	@DisplayName("a part-delivery on the day is on time, and the fill rate is what says it was short")
	void punctualButShortIsVisibleAsBoth() throws Exception {
		UUID vendor = vendor("Half Load Traders");
		for (int i = 0; i < 5; i++) {
			UUID po = order(vendor, days(-20), days(-10), "PARTIALLY_RECEIVED");
			UUID line = line(po, "40");
			// Ten of the forty kilos, on the day it was wanted.
			receiptLine(receipt(po, days(-10)), line, "10", "0", null);
		}

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].fillRatePercent").value(25))
				.andExpect(jsonPath("$.vendors[0].linesJudged").value(5));
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
	@DisplayName("drafts and cancellations are never held against a vendor")
	void draftsAndCancellationsAreOut() throws Exception {
		UUID vendor = vendor("Govind Wholesale");
		UUID received = order(vendor, days(-20), days(-10), "RECEIVED");
		fullyReceived(received, days(-11));
		line(order(vendor, days(-20), days(-10), "DRAFT"), "40");
		line(cancelled(vendor, days(-20), days(-10)), "40");

		mvc.perform(report())
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
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
	@DisplayName("a period whose end falls before its start is refused with KMS-4988")
	void aBackwardsPeriodIsRefused() throws Exception {
		mvc.perform(authed(get("/api/v1/vendor-performance")
						.param("from", today.toString())
						.param("to", today.minusDays(7).toString())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4988"));
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

	private UUID line(UUID poId, String quantity) {
		return admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'KG') RETURNING id
				""", UUID.class, tenant, poId, rice, quantity);
	}

	private UUID receipt(UUID poId, LocalDate receivedOn) {
		return admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, received_at)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, poId, UUID.randomUUID().toString(), staffId,
				receivedOn.atTime(12, 0).atZone(TEMPLE_ZONE).toOffsetDateTime());
	}

	private void receiptLine(UUID receiptId, UUID poLineId, String received, String rejected, String reason) {
		admin.update("""
				INSERT INTO goods_receipt_lines (
					tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, rejected_qty,
					reject_reason, unit)
				VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, ?, 'KG')
				""", tenant, receiptId, poLineId, rice, received, rejected, reason);
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
