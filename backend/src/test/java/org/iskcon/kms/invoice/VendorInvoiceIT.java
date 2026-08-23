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
				.andExpect(jsonPath("$.code").value("KMS-4923"));
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

	// ---------------------------------------------------------------------

	/** A SENT PO with one priced line, then a receipt of {@code receivedQty}, leaving it received. */
	private UUID receivedPo(String number, String orderedQty, String price, String receivedQty) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'RECEIVED', now(), ?) RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
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
