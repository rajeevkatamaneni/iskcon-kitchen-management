package org.iskcon.kms.invoice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Vendor invoice capture (E5-S8): invoices against a PO and direct (no-PO) invoices, the payment
 * queue, the informational price variance, the soft duplicate-number warning, and the overdue badge.
 *
 * <p>Since stage 6 (T-271) an invoice is recorded itemised — lines, deliveries, totals and a required
 * copy of the bill — and {@code InvoiceLinesIT} owns that shape. The variance tests here are about
 * invoices recorded <em>before</em> stage 6, which have only an amount against an order and must keep
 * reading exactly as they did; they are seeded in that old shape with SQL, since the endpoint no longer
 * writes it. Everything else records through the endpoint in the new shape.
 */
@AutoConfigureMockMvc
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
		// The tenant purge rather than a list of DELETEs: an itemised invoice now reaches the price
		// history and the market-rate history, both append-only, and the purge is the one path that
		// knows how to take those down (V86).
		admin.execute("SELECT delete_tenant_cascade('" + tenant + "')");
	}

	@Test
	@DisplayName("an invoice against a received PO is captured PENDING and joins the payment queue")
	void poInvoiceEntersQueue() throws Exception {
		UUID poId = receivedPo("PO-2026-0042", "30", "45.00", "30"); // 30 received @ 45 → expected 1350

		// Billed through its delivery, at the order's price: ₹1,350 against ₹1,350 delivered.
		mvc.perform(invoice(deliveryBill(poId, "INV-1", "30", "1350", "2026-08-20")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false))
				.andExpect(jsonPath("$.invoice.status").value("PENDING"))
				.andExpect(jsonPath("$.invoice.direct").value(false))
				.andExpect(jsonPath("$.invoice.purchaseOrderId").value(poId.toString()))
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

		UUID id = legacy(poId, "INV-2", "1400");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expectedValue").value(1350.0))
				.andExpect(jsonPath("$.variance").value(50.0)) // 1400 - 1350, shown not enforced
				// An invoice from before stage 6: no items, no deliveries, no totals, no bill.
				.andExpect(jsonPath("$.lines.length()").value(0))
				.andExpect(jsonPath("$.deliveries.length()").value(0))
				.andExpect(jsonPath("$.subTotal").doesNotExist())
				.andExpect(jsonPath("$.grandTotal").doesNotExist())
				.andExpect(jsonPath("$.bill").doesNotExist());
	}

	@Test
	@DisplayName("a credit note against the bill settles the variance it was raised for")
	void varianceIsNetOfCreditNotes() throws Exception {
		UUID poId = receivedPo("PO-2026-0044", "30", "45.00", "30"); // 30 received @ 45 → expected 1350
		String id = legacy(poId, "INV-3", "1400").toString();

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
		String id = legacy(poId, "INV-4", "1400").toString();

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
	@DisplayName("a voided bill against an order shows no expected value and no variance; a live one is unchanged")
	void aVoidedBillHasNoVariance() throws Exception {
		// T-207, Rajeev's decision for Phase B item 8. A struck bill is owed nothing, so it has nothing
		// to be out by; computed as before, it went on showing the discrepancy for ever and a voided
		// bill cannot be credited to clear it. Two bills against two identically priced orders: one
		// struck, one left standing, so the live figure is proved unchanged in the same run.
		UUID struckPo = receivedPo("PO-2026-0071", "30", "45.00", "30"); // expected 1350
		UUID livePo = receivedPo("PO-2026-0072", "30", "45.00", "30");   // expected 1350
		String struck = legacy(struckPo, "GW-V1", "1400").toString();
		legacy(livePo, "GW-V2", "1400").toString();

		// Before the void it carries the variance, so the assertion after it cannot pass on a bill
		// that never had one.
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", UUID.fromString(struck))))
				.andExpect(jsonPath("$.variance").value(50.0));

		signIn("uid-admin-a"); // striking a bill is MANAGE_VENDOR_PAYMENTS
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/void", UUID.fromString(struck)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Keyed against the wrong delivery.\"}"))
				.andExpect(status().isNoContent());

		// get(): both figures gone, while the order link and amount stay on the record.
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", UUID.fromString(struck))))
				.andExpect(jsonPath("$.status").value("VOIDED"))
				.andExpect(jsonPath("$.purchaseOrderId").value(struckPo.toString()))
				.andExpect(jsonPath("$.amount").value(1400.0))
				.andExpect(jsonPath("$.expectedValue").doesNotExist())
				.andExpect(jsonPath("$.variance").doesNotExist());

		// list(): the other read path, which has its own call to withVariance.
		mvc.perform(authed(get("/api/v1/vendor-invoices?status=VOIDED")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].expectedValue").doesNotExist())
				.andExpect(jsonPath("$[0].variance").doesNotExist());

		// The live bill beside it: exactly the figures it always had.
		mvc.perform(authed(get("/api/v1/vendor-invoices?status=PENDING")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].invoiceNumber").value("GW-V2"))
				.andExpect(jsonPath("$[0].expectedValue").value(1350.0))
				.andExpect(jsonPath("$[0].variance").value(50.0));
	}

	@Test
	@DisplayName("a direct (no-PO) invoice is recordable with a description and has no variance")
	void directInvoiceHasNoVariance() throws Exception {
		mvc.perform(invoice(direct("Cash market vegetables", "CASH-9", "2026-08-05", "800", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.direct").value(true))
				.andExpect(jsonPath("$.invoice.description").value("Cash market vegetables"))
				.andExpect(jsonPath("$.invoice.expectedValue").doesNotExist())
				.andExpect(jsonPath("$.invoice.variance").doesNotExist());
	}

	@Test
	@DisplayName("a direct invoice without a description is recorded: its lines say what was bought")
	void directInvoiceNeedsNoDescription() throws Exception {
		// Until stage 6 this was refused with KMS-400054, because the description was the only thing
		// a direct bill said about itself. Its item lines say it now (conductor's ruling for T-271).
		mvc.perform(invoice(direct(null, "CASH-10", "2026-08-05", "800", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.direct").value(true))
				.andExpect(jsonPath("$.invoice.description").doesNotExist());
	}

	@Test
	@DisplayName("a repeated invoice number for the same vendor warns softly but still records")
	void duplicateNumberWarnsSoftly() throws Exception {
		mvc.perform(invoice(direct("first", "DUP-1", "2026-08-01", "100", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false));

		mvc.perform(invoice(direct("second", "DUP-1", "2026-08-02", "120", null)))
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
		String struck = recordAndReturnId(direct("keyed as 1200 by mistake", "GW-77", "2026-08-01", "1200", null));

		signIn("uid-admin-a"); // striking a bill is MANAGE_VENDOR_PAYMENTS, which the store keeper has not
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/void", UUID.fromString(struck)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Keyed wrong; re-entering it.\"}"))
				.andExpect(status().isNoContent());
		signIn("uid-staff-a");

		mvc.perform(invoice(direct("the same bill, keyed right", "GW-77", "2026-08-01", "1250", null)))
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
		String struck = recordAndReturnId(direct("first attempt", "GW-88", "2026-08-01", "900", null));

		signIn("uid-admin-a");
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/void", UUID.fromString(struck)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Never received these goods.\"}"))
				.andExpect(status().isNoContent());
		signIn("uid-staff-a");

		// The re-entry: clean, so no warning.
		mvc.perform(invoice(direct("re-entered", "GW-88", "2026-08-02", "900", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false));

		// And a third under the same number, with a standing bill now in the way, warns as it always did.
		mvc.perform(invoice(direct("billed for it twice", "GW-88", "2026-08-03", "900", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(true));
	}

	@Test
	@DisplayName("an overdue PENDING invoice is flagged and filterable")
	void overdueIsFlagged() throws Exception {
		mvc.perform(invoice(direct("old bill", "OLD-1", "2026-01-01", "500", "2026-01-31")))
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
	//
	// An invoice no longer names an order: it bills deliveries, and the server names the order from
	// them (T-271). T-082's guarantee — a bill for Vendor A never computes its money from Vendor B's
	// order — now holds one step earlier: B's delivery cannot be billed on A's invoice at all.

	@Test
	@DisplayName("an invoice billing another vendor's delivery is refused, and nothing is recorded")
	void orderMustBelongToTheVendorOnTheInvoice() throws Exception {
		// Sri Traders' order, at a price of its own so that the variance it would have produced is
		// visibly not Govind's.
		UUID sriOrder = receivedPo(otherVendor, "PO-2026-0070", "30", "45.00", "30"); // worth 1350

		mvc.perform(invoice(deliveryBill(sriOrder, "INV-CROSS", "30", "1400", null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400169"));

		// Refused outright rather than recorded-and-flagged: a bill in the pay queue against the
		// wrong order is money owed computed from somebody else's delivery.
		mvc.perform(authed(get("/api/v1/vendor-invoices"))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("a delivery that does not exist is refused like any delivery that can't be billed")
	void anAbsentDeliveryIsNotBillable() throws Exception {
		// A random id is also what another temple's delivery looks like from here, since RLS hides it,
		// so the two must not be told apart. Both are "not billable": the screen offers only billable
		// deliveries, and reaching this means the page is stale.
		String body = deliveryBillJson(UUID.randomUUID(), UUID.randomUUID(), "INV-GHOST", "1", "100", null);
		mvc.perform(invoice(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400169"));
	}

	@Test
	@DisplayName("the variance is computed from the order actually on the invoice")
	void varianceComesFromTheOrderOnTheInvoice() throws Exception {
		// Two orders for the same vendor, worth different money: the arithmetic follows the order the
		// billed delivery is on, not the vendor.
		receivedPo(vendor, "PO-2026-0071", "30", "45.00", "30"); // worth 1350, and a decoy
		UUID quoted = receivedPo(vendor, "PO-2026-0072", "10", "20.00", "10"); // worth 200

		mvc.perform(invoice(deliveryBill(quoted, "INV-8", "10", "250", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.purchaseOrderId").value(quoted.toString()))
				.andExpect(jsonPath("$.invoice.expectedValue").value(200.0))
				.andExpect(jsonPath("$.invoice.variance").value(50.0));

		// And an invoice recorded before stage 6 against the same order reads the same way it always did.
		UUID old = legacy(quoted, "INV-8-OLD", "250");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", old)))
				.andExpect(jsonPath("$.expectedValue").value(200.0))
				.andExpect(jsonPath("$.variance").value(50.0));
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

	/**
	 * A direct bill in the stage-6 shape: one one-off line for the whole amount, no deliveries, and an
	 * uploaded copy of the bill. What these tests are about is the invoice row, not its items.
	 */
	private String direct(String description, String number, String date, String amount, String dueDate) {
		return "{\"vendorId\":\"" + vendor + "\""
				+ (description == null ? "" : ",\"description\":\"" + description + "\"")
				+ ",\"invoiceNumber\":\"" + number + "\",\"invoiceDate\":\"" + date + "\""
				+ (dueDate == null ? "" : ",\"dueDate\":\"" + dueDate + "\"")
				+ ",\"receiptIds\":[],\"lines\":[{\"description\":\"" + (description == null ? "Goods" : description)
				+ "\",\"billedQty\":1,\"unit\":\"PIECES\",\"amount\":" + amount + "}]"
				+ ",\"gstAmount\":0,\"otherCharges\":0,\"discount\":0,\"grandTotal\":" + amount
				+ ",\"billAttachmentId\":\"" + bill() + "\"}";
	}

	/** A stage-6 bill for the one delivery on {@code poId} (seeded by {@link #receivedPo}), its one line. */
	private String deliveryBill(UUID poId, String number, String qty, String amount, String dueDate) {
		UUID receipt = admin.queryForObject("SELECT id FROM goods_receipts WHERE po_id = ?", UUID.class, poId);
		UUID line = admin.queryForObject("SELECT id FROM goods_receipt_lines WHERE receipt_id = ?", UUID.class, receipt);
		return deliveryBillJson(receipt, line, number, qty, amount, dueDate);
	}

	private String deliveryBillJson(UUID receipt, UUID line, String number, String qty, String amount, String dueDate) {
		return "{\"vendorId\":\"" + vendor + "\",\"invoiceNumber\":\"" + number + "\",\"invoiceDate\":\"2026-08-01\""
				+ (dueDate == null ? "" : ",\"dueDate\":\"" + dueDate + "\"")
				+ ",\"receiptIds\":[\"" + receipt + "\"],\"lines\":[{\"goodsReceiptLineId\":\"" + line
				+ "\",\"ingredientId\":\"" + rice + "\",\"billedQty\":" + qty + ",\"unit\":\"KG\",\"amount\":"
				+ amount + "}],\"gstAmount\":0,\"otherCharges\":0,\"discount\":0,\"grandTotal\":" + amount
				+ ",\"billAttachmentId\":\"" + bill() + "\"}";
	}

	/** An uploaded, unclaimed copy of a bill, as POST /bill-uploads leaves it. */
	private UUID bill() {
		return admin.queryForObject("""
				INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, uploaded_by)
				VALUES (?, 'INVOICE_BILL', ?, 'application/pdf', 1024, ?) RETURNING id
				""", UUID.class, tenant, "tenants/" + tenant + "/attachments/" + UUID.randomUUID(), staffId);
	}

	/**
	 * An invoice as the endpoint wrote it before stage 6: an amount against an order, and nothing else
	 * — no lines, totals or bill. Seeded directly because the endpoint no longer writes this shape, and
	 * those invoices exist in every temple's records and must go on reading as they did.
	 */
	private UUID legacy(UUID poId, String number, String amount) {
		return admin.queryForObject("""
				INSERT INTO vendor_invoices (tenant_id, vendor_id, po_id, invoice_number, invoice_date, amount, created_by)
				VALUES (?, ?, ?, ?, DATE '2026-08-01', ?::numeric, ?) RETURNING id
				""", UUID.class, tenant, vendor, poId, number, amount, staffId);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

}
