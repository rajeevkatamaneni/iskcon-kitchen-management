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
 * Vendor invoice payment recording (E7-S8): full and partial payments, PAID only at the full amount,
 * overpayment refused, audited and immutable (compensating entry), and the aging payables view.
 */
@AutoConfigureMockMvc
class InvoicePaymentIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID vendor;
	private UUID adminId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		adminId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		// attachments first: its foreign key to invoice_payments is RESTRICT.
		admin.execute("DELETE FROM attachments");
		admin.execute("DELETE FROM invoice_payments");
		admin.execute("DELETE FROM vendor_invoices");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a partial then completing payment flips the invoice to PAID, and is audited")
	void partialThenFullPays() throws Exception {
		UUID inv = invoice("INV-1", "1000", null);

		mvc.perform(pay(inv, "600", "UPI")).andExpect(status().isCreated());
		assert invoiceStatus(inv).equals("PENDING") : "partly paid stays pending";

		mvc.perform(pay(inv, "400", "BANK_TRANSFER")).andExpect(status().isCreated());
		assert invoiceStatus(inv).equals("PAID") : "fully paid flips to PAID";

		Integer audits = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INVOICE_PAYMENT_RECORDED' AND entity_id = ?",
				Integer.class, inv);
		assert audits == 2 : "each payment audited, was " + audits;
	}

	@Test
	@DisplayName("overpayment is refused")
	void overpaymentRefused() throws Exception {
		UUID inv = invoice("INV-2", "1000", null);
		mvc.perform(pay(inv, "1200", "CASH"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400069"));
	}

	@Test
	@DisplayName("paying an already-paid invoice is refused")
	void alreadyPaidRefused() throws Exception {
		UUID inv = invoice("INV-3", "500", null);
		mvc.perform(pay(inv, "500", "UPI")).andExpect(status().isCreated());
		mvc.perform(pay(inv, "100", "UPI"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400070"));
	}

	@Test
	@DisplayName("a compensating negative entry reopens a paid invoice")
	void compensatingReopens() throws Exception {
		UUID inv = invoice("INV-4", "500", null);
		String created = mvc.perform(pay(inv, "500", "CHEQUE")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID payment = UUID.fromString(created.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
		assert invoiceStatus(inv).equals("PAID");
		// Through Reverse, which writes the compensating negative row itself: a hand-entered negative
		// amount is refused since T-280 (PaymentProofIT has that refusal).
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/payments/{paymentId}/reverse", inv, payment))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"The cheque bounced.\"}"))
				.andExpect(status().isNoContent());
		assert invoiceStatus(inv).equals("PENDING") : "a reversal should reopen it";
	}

	@Test
	@DisplayName("the payables view buckets invoices by how overdue they are")
	void payablesAging() throws Exception {
		invoice("CUR", "100", java.time.LocalDate.now().plusDays(5).toString());
		invoice("D15", "100", java.time.LocalDate.now().minusDays(15).toString());
		invoice("D40", "100", java.time.LocalDate.now().minusDays(40).toString());

		mvc.perform(authed(get("/api/v1/payables")))
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[?(@.invoiceNumber=='CUR')].agingBucket").value("CURRENT"))
				.andExpect(jsonPath("$[?(@.invoiceNumber=='D15')].agingBucket").value("DUE_1_30"))
				.andExpect(jsonPath("$[?(@.invoiceNumber=='D40')].agingBucket").value("OVERDUE_31_PLUS"));
	}

	// ---------------------------------------------------------------------

	private UUID invoice(String number, String amount, String dueDate) {
		return admin.queryForObject("""
				INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, description, invoice_number,
					invoice_date, amount, due_date, status, created_by)
				VALUES (?, ?, true, 'cash market', ?, CURRENT_DATE, ?::numeric, ?::date, 'PENDING', ?)
				RETURNING id
				""", UUID.class, tenant, vendor, number, amount, dueDate, adminId);
	}

	private MockHttpServletRequestBuilder pay(UUID invoiceId, String amount, String method) {
		return authed(post("/api/v1/vendor-invoices/{id}/payments", invoiceId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"paidOn\":\"" + java.time.LocalDate.now() + "\",\"amount\":" + amount
						+ ",\"method\":\"" + method + "\"" + proofFields(amount, method) + "}");
	}

	/**
	 * The proof a payment of this method needs since stage 6 (R-PAY-2), as JSON fields to add to the
	 * body. Written straight into {@code attachments} as unclaimed uploads, because what these tests
	 * are about is the money, not the upload: {@code PaymentProofIT} goes through the real upload
	 * endpoint. Every payment carries it: a negative amount is no longer something this endpoint takes
	 * (T-280), so there is no case without.
	 */
	private String proofFields(String amount, String method) {
		if (method.equals("CASH")) {
			return ",\"receivedByName\":\"Manjunath K.\",\"signedNoteAttachmentId\":\""
					+ unclaimedUpload("CASH_SIGNED_NOTE") + "\",\"receiverPhotoAttachmentId\":\""
					+ unclaimedUpload("CASH_RECEIVER_PHOTO") + "\"";
		}
		return ",\"proofAttachmentId\":\"" + unclaimedUpload("PAYMENT_PROOF") + "\"";
	}

	private UUID unclaimedUpload(String kind) {
		UUID id = UUID.randomUUID();
		admin.update("""
				INSERT INTO attachments (id, tenant_id, kind, storage_key, content_type, size_bytes, uploaded_by)
				VALUES (?, ?, ?, ?, 'image/png', 64, ?)
				""", id, tenant, kind, "tenants/" + tenant + "/attachments/" + id, adminId);
		return id;
	}

	private String invoiceStatus(UUID invoiceId) {
		return admin.queryForObject("SELECT status FROM vendor_invoices WHERE id = ?", String.class, invoiceId);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
