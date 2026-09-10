package org.iskcon.kms.invoice;

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
 * Vendor invoice capture (E5-S8): invoices against a PO and direct (no-PO) invoices, the payment
 * queue, the informational price variance, the soft duplicate-number warning, and the overdue badge.
 */
@AutoConfigureMockMvc
@Import(VendorInvoiceIT.StubVerifierConfiguration.class)
class VendorInvoiceIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID vendor;
	private UUID otherVendor;

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
		// Recording a credit note is an admin act, so the variance-after-credit test signs in as one
		// rather than as the kitchen staff the rest of this class uses.
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin-a', 'Admin A', 'admin-a@example.com', '+919876500083', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		// A second supplier, for T-082: an invoice naming one vendor while quoting the other's order
		// used to be accepted, because the vendor and the order were checked for existence
		// independently and never against each other.
		otherVendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Sri Traders', '+919812345679')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM vendor_invoices");
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("an invoice against a received PO is captured PENDING and joins the payment queue")
	void poInvoiceEntersQueue() throws Exception {
		UUID poId = receivedPo("PO-2026-0042", "30", "45.00", "30"); // 30 received @ 45 → expected 1350

		mvc.perform(invoice("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + poId
						+ "\",\"invoiceNumber\":\"INV-1\",\"invoiceDate\":\"2026-08-01\",\"amount\":1350,"
						+ "\"dueDate\":\"2026-08-20\",\"scanRef\":\"gcs://scans/inv-1.pdf\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false))
				.andExpect(jsonPath("$.invoice.status").value("PENDING"))
				.andExpect(jsonPath("$.invoice.direct").value(false))
				.andExpect(jsonPath("$.invoice.expectedValue").value(1350.0))
				.andExpect(jsonPath("$.invoice.variance").value(0.0));

		// The payment queue (E7 contract): PENDING invoices are listed.
		mvc.perform(authed(get("/api/v1/vendor-invoices?status=PENDING")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].invoiceNumber").value("INV-1"));
	}

	@Test
	@DisplayName("a price mismatch surfaces an informational variance without blocking")
	void varianceSurfacesWhenPricesDiffer() throws Exception {
		UUID poId = receivedPo("PO-2026-0043", "30", "45.00", "30"); // expected 1350

		mvc.perform(invoice("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + poId
						+ "\",\"invoiceNumber\":\"INV-2\",\"invoiceDate\":\"2026-08-01\",\"amount\":1400}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.expectedValue").value(1350.0))
				.andExpect(jsonPath("$.invoice.variance").value(50.0)); // 1400 - 1350, shown not enforced
	}

	@Test
	@DisplayName("a credit note against the bill settles the variance it was raised for")
	void varianceIsNetOfCreditNotes() throws Exception {
		UUID poId = receivedPo("PO-2026-0044", "30", "45.00", "30"); // 30 received @ 45 → expected 1350
		String id = recordAndReturnId("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + poId
				+ "\",\"invoiceNumber\":\"INV-3\",\"invoiceDate\":\"2026-08-01\",\"amount\":1400}");

		// Before the credit: the discrepancy the variance exists to surface. Asserted here as well as
		// in varianceSurfacesWhenPricesDiffer so that a negative control on the fix cannot pass by
		// silently deleting the uncredited behaviour along with the credited one.
		signIn("uid-admin-a");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id)))
				.andExpect(jsonPath("$.creditedAmount").value(0.00))
				.andExpect(jsonPath("$.variance").value(50.0));

		// The vendor credits the ₹50 the variance was raised about — a short delivery, argued and
		// agreed. The bill is now for what the goods were worth, and the query is closed.
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/credit", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50,\"reason\":\"Short by one sack, agreed.\"}"))
				.andExpect(status().isNoContent());

		// get(): the variance is computed on what is owed, 1400 - 50 - 1350.
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id)))
				.andExpect(jsonPath("$.creditedAmount").value(50.00))
				.andExpect(jsonPath("$.amount").value(1400.00))
				// expectedValue is untouched: the goods received are still worth what they were worth.
				.andExpect(jsonPath("$.expectedValue").value(1350.0))
				.andExpect(jsonPath("$.variance").value(0.0));

		// list(): the same arithmetic on the other read path, which has its own call to withVariance.
		mvc.perform(authed(get("/api/v1/vendor-invoices")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].expectedValue").value(1350.0))
				.andExpect(jsonPath("$[0].variance").value(0.0));
	}

	@Test
	@DisplayName("a partial credit leaves the part of the variance that is still in dispute")
	void aPartialCreditLeavesTheRemainingVariance() throws Exception {
		UUID poId = receivedPo("PO-2026-0045", "30", "45.00", "30"); // expected 1350
		String id = recordAndReturnId("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + poId
				+ "\",\"invoiceNumber\":\"INV-4\",\"invoiceDate\":\"2026-08-01\",\"amount\":1400}");

		signIn("uid-admin-a");
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/credit", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":20,\"reason\":\"Part of the shortfall conceded.\"}"))
				.andExpect(status().isNoContent());

		// 1400 - 20 - 1350: the ₹30 still being argued about goes on showing, which is the whole
		// point of netting rather than clearing the variance whenever any credit exists.
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id)))
				.andExpect(jsonPath("$.variance").value(30.0));
		mvc.perform(authed(get("/api/v1/vendor-invoices")))
				.andExpect(jsonPath("$[0].variance").value(30.0));
	}

	@Test
	@DisplayName("a direct (no-PO) invoice is recordable with a description and has no variance")
	void directInvoiceHasNoVariance() throws Exception {
		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"Cash market vegetables\",\"invoiceNumber\":\"CASH-9\","
						+ "\"invoiceDate\":\"2026-08-05\",\"amount\":800}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.direct").value(true))
				.andExpect(jsonPath("$.invoice.description").value("Cash market vegetables"))
				.andExpect(jsonPath("$.invoice.expectedValue").doesNotExist())
				.andExpect(jsonPath("$.invoice.variance").doesNotExist());
	}

	@Test
	@DisplayName("a direct invoice without a description is rejected")
	void directInvoiceNeedsDescription() throws Exception {
		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"invoiceNumber\":\"CASH-10\",\"invoiceDate\":\"2026-08-05\",\"amount\":800}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400054"));
	}

	@Test
	@DisplayName("a repeated invoice number for the same vendor warns softly but still records")
	void duplicateNumberWarnsSoftly() throws Exception {
		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"first\",\"invoiceNumber\":\"DUP-1\",\"invoiceDate\":\"2026-08-01\",\"amount\":100}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false));

		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"second\",\"invoiceNumber\":\"DUP-1\",\"invoiceDate\":\"2026-08-02\",\"amount\":120}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(true)); // soft — still created

		mvc.perform(authed(get("/api/v1/vendor-invoices"))).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("re-entering the number of a bill that was struck does not warn")
	void aVoidedBillIsNotDuplicated() throws Exception {
		// The ordinary correction path, as Rajeev described it when he ruled on this (2026-09-10):
		// record a bill, spot a mistake, void it, re-enter it under the same number. Voiding is a
		// mark on the row and nothing is ever deleted, so the struck bill is still there to be
		// counted — and counting it warned the clerk that they had duplicated a bill the temple had
		// just said was never owed. "A warning that fires when somebody is being careful is one they
		// learn to dismiss."
		String struck = recordAndReturnId("{\"vendorId\":\"" + vendor
				+ "\",\"description\":\"keyed as 1200 by mistake\",\"invoiceNumber\":\"GW-77\","
				+ "\"invoiceDate\":\"2026-08-01\",\"amount\":1200}");

		signIn("uid-admin-a"); // striking a bill is MANAGE_VENDOR_PAYMENTS, which the store keeper has not
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/void", UUID.fromString(struck)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Keyed wrong; re-entering it.\"}"))
				.andExpect(status().isNoContent());
		signIn("uid-staff-a");

		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"the same bill, keyed right\",\"invoiceNumber\":\"GW-77\","
						+ "\"invoiceDate\":\"2026-08-01\",\"amount\":1250}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false));

		// Nothing was hidden to achieve it: both rows are still in the ledger, the struck one still
		// saying on its face that it was struck. The warning changed, the record did not.
		mvc.perform(authed(get("/api/v1/vendor-invoices"))).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("a second standing bill under one number still warns, struck ones aside")
	void aStandingBillStillWarnsEvenWithAStruckOneBeside() throws Exception {
		// The other half, and the one that stops the fix being a deletion of the feature: excluding
		// voided rows must not excuse the case this check exists for — being billed twice for one
		// delivery, which is money out of the door.
		String struck = recordAndReturnId("{\"vendorId\":\"" + vendor
				+ "\",\"description\":\"first attempt\",\"invoiceNumber\":\"GW-88\","
				+ "\"invoiceDate\":\"2026-08-01\",\"amount\":900}");

		signIn("uid-admin-a");
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/void", UUID.fromString(struck)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Never received these goods.\"}"))
				.andExpect(status().isNoContent());
		signIn("uid-staff-a");

		// The re-entry: clean, so no warning.
		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"re-entered\",\"invoiceNumber\":\"GW-88\","
						+ "\"invoiceDate\":\"2026-08-02\",\"amount\":900}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false));

		// And a third under the same number, with a standing bill now in the way, warns as it always did.
		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"billed for it twice\",\"invoiceNumber\":\"GW-88\","
						+ "\"invoiceDate\":\"2026-08-03\",\"amount\":900}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(true));
	}

	@Test
	@DisplayName("an overdue PENDING invoice is flagged and filterable")
	void overdueIsFlagged() throws Exception {
		mvc.perform(invoice("{\"vendorId\":\"" + vendor
						+ "\",\"description\":\"old bill\",\"invoiceNumber\":\"OLD-1\",\"invoiceDate\":\"2026-01-01\","
						+ "\"amount\":500,\"dueDate\":\"2026-01-31\"}"))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/vendor-invoices?overdue=true")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].overdue").value(true));
	}

	@Test
	@DisplayName("a volunteer cannot capture invoices")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/vendor-invoices"))).andExpect(status().isForbidden());
	}

	// ---- The order must belong to the vendor being invoiced (T-082) ------

	@Test
	@DisplayName("an invoice quoting another vendor's purchase order is refused, and nothing is recorded")
	void orderMustBelongToTheVendorOnTheInvoice() throws Exception {
		// Sri Traders' order, at a price of its own so that the variance it would have produced is
		// visibly not Govind's.
		UUID sriOrder = receivedPo(otherVendor, "PO-2026-0070", "30", "45.00", "30"); // worth 1350

		mvc.perform(invoice("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + sriOrder
						+ "\",\"invoiceNumber\":\"INV-CROSS\",\"invoiceDate\":\"2026-08-01\",\"amount\":1400}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400145"));

		// Refused outright rather than recorded-and-flagged: a bill in the pay queue against the
		// wrong order is money owed computed from somebody else's delivery.
		mvc.perform(authed(get("/api/v1/vendor-invoices"))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("an order that does not exist at all is still a not-found, not a mismatch")
	void anAbsentOrderIsStillNotFound() throws Exception {
		// The two failures stayed distinct when the check was folded into one query. A random id is
		// also what a cross-tenant order looks like from here, since RLS hides it.
		mvc.perform(invoice("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + UUID.randomUUID()
						+ "\",\"invoiceNumber\":\"INV-GHOST\",\"invoiceDate\":\"2026-08-01\",\"amount\":100}"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("the variance is computed from the order actually on the invoice")
	void varianceComesFromTheOrderOnTheInvoice() throws Exception {
		// Two orders for the same vendor, worth different money. Before the guard the wrong one
		// could be quoted; this asserts the arithmetic follows po_id rather than the vendor.
		receivedPo(vendor, "PO-2026-0071", "30", "45.00", "30"); // worth 1350, and a decoy
		UUID quoted = receivedPo(vendor, "PO-2026-0072", "10", "20.00", "10"); // worth 200

		mvc.perform(invoice("{\"vendorId\":\"" + vendor + "\",\"purchaseOrderId\":\"" + quoted
						+ "\",\"invoiceNumber\":\"INV-8\",\"invoiceDate\":\"2026-08-01\",\"amount\":250}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.expectedValue").value(200.0))
				.andExpect(jsonPath("$.invoice.variance").value(50.0));
	}

	@Test
	@DisplayName("the order picker offers one vendor's open orders — never a draft, a cancellation or another vendor's")
	void openOrdersAreOnlyThisVendorsAndOnlyInvoiceable() throws Exception {
		UUID sent = order(vendor, "PO-2026-0080", "SENT");
		UUID part = order(vendor, "PO-2026-0081", "PARTIALLY_RECEIVED");
		UUID received = order(vendor, "PO-2026-0082", "RECEIVED");
		order(vendor, "PO-2026-0083", "DRAFT"); // never sent to the vendor, so never billed for
		order(vendor, "PO-2026-0084", "CANCELLED"); // withdrawn; a bill against it is a dispute
		order(otherVendor, "PO-2026-0085", "RECEIVED"); // the other supplier's, and the whole point

		mvc.perform(authed(get("/api/v1/purchase-orders?openOnly=true&vendorId={v}", vendor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[?(@.id == '" + sent + "')]").exists())
				.andExpect(jsonPath("$[?(@.id == '" + part + "')]").exists())
				.andExpect(jsonPath("$[?(@.id == '" + received + "')]").exists());

		// Unfiltered, the same list still holds everything — the narrowing is the caller's, not a
		// change to what an order list means.
		mvc.perform(authed(get("/api/v1/purchase-orders")))
				.andExpect(jsonPath("$.length()").value(6));

		// And the other vendor gets its own one order, which is the re-filter the screen performs.
		mvc.perform(authed(get("/api/v1/purchase-orders?openOnly=true&vendorId={v}", otherVendor)))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].poNumber").value("PO-2026-0085"));
	}

	// ---------------------------------------------------------------------

	/** A SENT PO with one priced line, then a receipt of {@code receivedQty}, leaving it received. */
	private UUID receivedPo(String number, String orderedQty, String price, String receivedQty) {
		return receivedPo(vendor, number, orderedQty, price, receivedQty);
	}

	/** The same, for whichever vendor is named — the mismatch tests need two suppliers. */
	private UUID receivedPo(UUID forVendor, String number, String orderedQty, String price, String receivedQty) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'RECEIVED', now(), ?) RETURNING id
				""", UUID.class, tenant, number, forVendor, staffId);
		UUID line = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price)
				VALUES (?, ?, ?, ?::numeric, 'KG', ?::numeric) RETURNING id
				""", UUID.class, tenant, poId, rice, orderedQty, price);
		UUID receipt = admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, poId, "seed-" + number, staffId);
		admin.update("""
				INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit)
				VALUES (?, ?, ?, ?, ?::numeric, 'KG')
				""", tenant, receipt, line, rice, receivedQty);
		return poId;
	}

	/** A bare header in whatever state the test needs, with no lines and no receipt. */
	private UUID order(UUID forVendor, String number, String status) {
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, ?, CASE WHEN ? = 'DRAFT' THEN NULL ELSE now() END, ?) RETURNING id
				""", UUID.class, tenant, number, forVendor, status, status, staffId);
	}

	/** Records an invoice and returns its id, for the tests that then read it back. */
	private String recordAndReturnId(String json) throws Exception {
		String body = mvc.perform(invoice(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"invoice\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
	}

	private MockHttpServletRequestBuilder invoice(String json) {
		return authed(post("/api/v1/vendor-invoices")).contentType(MediaType.APPLICATION_JSON).content(json);
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
