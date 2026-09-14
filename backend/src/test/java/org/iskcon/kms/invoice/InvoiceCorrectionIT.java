package org.iskcon.kms.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
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
 * Correcting the money side of a vendor invoice (T-010): withdrawing a bill that was never owed,
 * reducing one the vendor has credited, and undoing a payment that did not happen after all.
 *
 * <p>Against a real PostgreSQL under row-level security, as the unprivileged application role,
 * because two of the three facts under test are database behaviours and mocking them would prove
 * nothing: that {@code invoice_payments} refuses to be marked, and that it accepts the negative row
 * V40 designed for instead.
 */
@AutoConfigureMockMvc
class InvoiceCorrectionIT extends AbstractIntegrationTest {

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
		stubVerifier.accept("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM invoice_payments");
		admin.execute("DELETE FROM vendor_invoices");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---------------------------------------------------------------------
	// Reversing a payment
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("reversing the payment that paid a bill puts the bill back in the queue")
	void reversalReopensAPaidInvoice() throws Exception {
		UUID inv = invoice("INV-1", "500", null);
		UUID payment = pay(inv, "500", "CHEQUE");
		assertThat(invoiceStatus(inv)).isEqualTo("PAID");

		mvc.perform(reverse(inv, payment, "The cheque bounced.")).andExpect(status().isNoContent());

		assertThat(invoiceStatus(inv)).as("a reversed payment reopens the bill").isEqualTo("PENDING");
		assertThat(paidToDate(inv)).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("the reversed payment is left exactly as it was, and the correction names it")
	void theOriginalRowIsUntouched() throws Exception {
		UUID inv = invoice("INV-2", "500", null);
		UUID payment = pay(inv, "500", "BANK_TRANSFER");

		// The whole row, not the columns this test happens to think of. Nothing about an
		// append-only entry may change, including the columns added by the migration under test.
		String before = rowJson(payment);

		mvc.perform(reverse(inv, payment, "Paid against the wrong bill.")).andExpect(status().isNoContent());

		assertThat(rowJson(payment)).as("history is never edited").isEqualTo(before);

		Map<String, Object> correction = admin.queryForMap(
				"SELECT amount, reverses, reverse_reason, paid_on FROM invoice_payments WHERE reverses = ?",
				payment);
		assertThat((BigDecimal) correction.get("amount")).isEqualByComparingTo("-500");
		assertThat(correction.get("reverses")).isEqualTo(payment);
		assertThat(correction.get("reverse_reason")).isEqualTo("Paid against the wrong bill.");
		assertThat(correction.get("paid_on"))
				.as("the pair nets to zero in the period the payment was reported in")
				.isEqualTo(admin.queryForObject(
						"SELECT paid_on FROM invoice_payments WHERE id = ?", java.sql.Date.class, payment));

		Integer rows = admin.queryForObject(
				"SELECT count(*) FROM invoice_payments WHERE invoice_id = ?", Integer.class, inv);
		assertThat(rows).as("nothing was deleted — two rows, not one").isEqualTo(2);
	}

	@Test
	@DisplayName("a payment cannot be reversed twice")
	void secondReversalRefused() throws Exception {
		UUID inv = invoice("INV-3", "500", null);
		UUID payment = pay(inv, "500", "UPI");

		mvc.perform(reverse(inv, payment, "Bounced.")).andExpect(status().isNoContent());
		mvc.perform(reverse(inv, payment, "Bounced again."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400133"));

		Integer corrections = admin.queryForObject(
				"SELECT count(*) FROM invoice_payments WHERE reverses = ?", Integer.class, payment);
		assertThat(corrections).isEqualTo(1);
	}

	@Test
	@DisplayName("the compensating row is itself a correction, and is not reversible")
	void reversingACorrectionRefused() throws Exception {
		UUID inv = invoice("INV-4", "500", null);
		UUID payment = pay(inv, "500", "UPI");
		mvc.perform(reverse(inv, payment, "Bounced.")).andExpect(status().isNoContent());

		UUID correction = admin.queryForObject(
				"SELECT id FROM invoice_payments WHERE reverses = ?", UUID.class, payment);
		mvc.perform(reverse(inv, correction, "Undo the undo."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400133"));
	}

	@Test
	@DisplayName("both ends of a reversal are visible on the payment list")
	void bothEndsOfTheReversalAreReadable() throws Exception {
		UUID inv = invoice("INV-5", "500", null);
		UUID payment = pay(inv, "500", "CHEQUE");
		mvc.perform(reverse(inv, payment, "The cheque bounced.")).andExpect(status().isNoContent());

		UUID correction = admin.queryForObject(
				"SELECT id FROM invoice_payments WHERE reverses = ?", UUID.class, payment);

		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/payments", inv)))
				.andExpect(jsonPath("$.length()").value(2))
				// The original knows it was undone, without a screen having to scan the list for a
				// row that names it.
				.andExpect(jsonPath("$[?(@.id=='" + payment + "')].reversedBy").value(correction.toString()))
				.andExpect(jsonPath("$[?(@.id=='" + correction + "')].reverses").value(payment.toString()))
				.andExpect(jsonPath("$[?(@.id=='" + correction + "')].amount").value(-500.00))
				.andExpect(jsonPath("$[?(@.id=='" + correction + "')].reverseReason")
						.value("The cheque bounced."));
	}

	// ---------------------------------------------------------------------
	// Voiding and crediting
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("a voided bill leaves the payables list and says on its face that it was struck")
	void voidingRemovesItFromPayables() throws Exception {
		UUID inv = invoice("INV-6", "700", null);
		mvc.perform(authed(get("/api/v1/payables")))
				.andExpect(jsonPath("$[?(@.invoiceNumber=='INV-6')].outstanding").value(700.00));

		mvc.perform(voidInvoice(inv, "Billed twice for the same delivery."))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/payables"))).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", inv)))
				.andExpect(jsonPath("$.status").value("VOIDED"))
				.andExpect(jsonPath("$.voidReason").value("Billed twice for the same delivery."))
				.andExpect(jsonPath("$.voidedAt").isNotEmpty())
				.andExpect(jsonPath("$.overdue").value(false));
	}

	@Test
	@DisplayName("a bill cannot be voided twice")
	void secondVoidRefused() throws Exception {
		UUID inv = invoice("INV-7", "700", null);
		mvc.perform(voidInvoice(inv, "Never received.")).andExpect(status().isNoContent());
		mvc.perform(voidInvoice(inv, "Still never received."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400132"));

		assertThat(admin.queryForObject(
				"SELECT void_reason FROM vendor_invoices WHERE id = ?", String.class, inv))
				.as("the first reason stands; the second act was refused, not swallowed")
				.isEqualTo("Never received.");
	}

	@Test
	@DisplayName("a struck bill cannot be paid")
	void aStruckBillCannotBePaid() throws Exception {
		// Reshaped by T-206. This used to pay ₹200 first and then void, which is now refused; the
		// half of it that still stands is that a bill struck as never owed takes no payment.
		UUID inv = invoice("INV-8", "500", null);
		mvc.perform(voidInvoice(inv, "The goods went back.")).andExpect(status().isNoContent());

		mvc.perform(payRequest(inv, "300", "CASH"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400132"));
		assertThat(paidToDate(inv)).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a bill struck before T-206 with money still paid on it stays struck when that is reversed")
	void aReversalDoesNotBringAStruckBillBack() throws Exception {
		// The other half of the old test, which the API can no longer set up: since T-206 a bill with
		// money paid against it cannot be voided. Bills struck before that can still be in this state
		// on a live tenant, and reversing their payments must recover the money without resurrecting
		// the bill — the VOIDED guard in restateStatus is what stops it reappearing in the pay queue.
		// So the old state is written directly, in the shape V103's void constraint requires.
		UUID inv = invoice("INV-8B", "500", null);
		UUID payment = admin.queryForObject("""
				INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by)
				VALUES (?, ?, CURRENT_DATE, 200, 'CASH', ?) RETURNING id
				""", UUID.class, tenant, inv, adminId);
		admin.update("""
				UPDATE vendor_invoices
				SET status = 'VOIDED', voided_at = now(), voided_by = ?, void_reason = 'The goods went back.'
				WHERE id = ?
				""", adminId, inv);

		// Recovering the ₹200 is still a legitimate act; resurrecting the bill is not.
		mvc.perform(reverse(inv, payment, "Money returned.")).andExpect(status().isNoContent());
		assertThat(invoiceStatus(inv)).isEqualTo("VOIDED");
		assertThat(paidToDate(inv)).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a bill with money still paid against it cannot be voided, and nothing is written")
	void voidRefusedWhilePaymentsStand() throws Exception {
		// Rajeev's decision for Phase B item 7 (T-206). Voiding says the bill was never owed; a payment
		// standing against it says it was owed and paid. Both a bill paid in full and a part-paid one,
		// because PAID and PENDING are different statuses and the refusal must not hang on either.
		UUID paidInFull = invoice("INV-20", "500", null);
		pay(paidInFull, "500", "UPI");
		UUID partPaid = invoice("INV-21", "500", null);
		pay(partPaid, "200", "CASH");

		for (UUID inv : new UUID[] {paidInFull, partPaid}) {
			String billBefore = invoiceJson(inv);
			String paymentsBefore = paymentsJson(inv);

			mvc.perform(voidInvoice(inv, "Never received."))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("KMS-400154"))
					.andExpect(jsonPath("$.message").value("This bill has payments that have not been reversed."))
					.andExpect(jsonPath("$.action").value("Reverse the payments on this bill before voiding it."));

			// The whole row on both sides, not the columns this test happens to think of: a refusal
			// that wrote updated_at, or half a void mark, is a refusal that changed the bill.
			assertThat(invoiceJson(inv)).as("the bill is unchanged").isEqualTo(billBefore);
			assertThat(paymentsJson(inv)).as("its payments are unchanged").isEqualTo(paymentsBefore);
			assertThat(audit("INVOICE_VOIDED", inv)).as("no audit entry for an act that did not happen").isZero();
		}
		assertThat(invoiceStatus(paidInFull)).isEqualTo("PAID");
		assertThat(invoiceStatus(partPaid)).isEqualTo("PENDING");

		// Reversing is per payment and the refusal reads the net, so a bill paid twice with only one of
		// the two reversed still has ₹100 standing against it and is still refused.
		UUID twice = invoice("INV-22", "500", null);
		UUID first = pay(twice, "300", "UPI");
		pay(twice, "100", "UPI");
		mvc.perform(reverse(twice, first, "Paid against the wrong bill.")).andExpect(status().isNoContent());
		mvc.perform(voidInvoice(twice, "Never received."))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400154"));
	}

	@Test
	@DisplayName("once every payment on a bill is reversed, the bill can be voided")
	void voidAllowedOnceEveryPaymentIsReversed() throws Exception {
		UUID inv = invoice("INV-23", "500", null);
		UUID payment = pay(inv, "500", "CHEQUE");
		mvc.perform(voidInvoice(inv, "Never received.")).andExpect(status().isConflict());

		mvc.perform(reverse(inv, payment, "The cheque bounced.")).andExpect(status().isNoContent());
		mvc.perform(voidInvoice(inv, "Never received.")).andExpect(status().isNoContent());

		assertThat(invoiceStatus(inv)).isEqualTo("VOIDED");
		assertThat(audit("INVOICE_VOIDED", inv)).isEqualTo(1);
		assertThat(admin.queryForObject("""
				SELECT before_state ->> 'paidToDate' FROM audit_events
				WHERE action = 'INVOICE_VOIDED' AND entity_id = ?
				""", String.class, inv)).as("the audit still records what was paid when it was struck").isEqualTo("0.00");

		// The hand-entered compensating entry V40 has allowed since 2025 carries no link to the payment
		// it corrects, but it nets the same way, and the refusal reads the net.
		UUID corrected = invoice("INV-24", "500", null);
		pay(corrected, "250", "UPI");
		pay(corrected, "-250", "UPI");
		mvc.perform(voidInvoice(corrected, "Billed twice.")).andExpect(status().isNoContent());
		assertThat(invoiceStatus(corrected)).isEqualTo("VOIDED");
	}

	@Test
	@DisplayName("a credit note reduces what is owed and lets a part-paid bill settle")
	void creditSettlesABill() throws Exception {
		UUID inv = invoice("INV-9", "1000", null);
		pay(inv, "600", "UPI");
		assertThat(invoiceStatus(inv)).isEqualTo("PENDING");

		mvc.perform(credit(inv, "400", "Short by two sacks.")).andExpect(status().isNoContent());

		assertThat(invoiceStatus(inv)).as("owed is the amount less the credit").isEqualTo("PAID");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", inv)))
				.andExpect(jsonPath("$.creditedAmount").value(400.00))
				.andExpect(jsonPath("$.status").value("PAID"))
				// A credit is not a void: the bill was owed, and the mark stays empty.
				.andExpect(jsonPath("$.voidedAt").doesNotExist());
		mvc.perform(authed(get("/api/v1/payables"))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("a credit cannot take what is owed below what has already been paid")
	void creditBeyondWhatIsOwedRefused() throws Exception {
		UUID inv = invoice("INV-10", "1000", null);
		pay(inv, "800", "UPI");

		mvc.perform(credit(inv, "500", "Too much.")).andExpect(status().isBadRequest());
		assertThat(admin.queryForObject(
				"SELECT credited_amount FROM vendor_invoices WHERE id = ?", BigDecimal.class, inv))
				.isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("all three acts are audited, each under its own action")
	void allThreeActsAreAudited() throws Exception {
		UUID credited = invoice("INV-11", "1000", null);
		mvc.perform(credit(credited, "100", "Damaged sack.")).andExpect(status().isNoContent());

		UUID struck = invoice("INV-12", "300", null);
		mvc.perform(voidInvoice(struck, "Not our bill.")).andExpect(status().isNoContent());

		UUID reversed = invoice("INV-13", "300", null);
		UUID payment = pay(reversed, "300", "CHEQUE");
		mvc.perform(reverse(reversed, payment, "Bounced.")).andExpect(status().isNoContent());

		assertThat(audit("INVOICE_CREDITED", credited)).isEqualTo(1);
		assertThat(audit("INVOICE_VOIDED", struck)).isEqualTo(1);
		assertThat(audit("INVOICE_PAYMENT_VOIDED", reversed)).isEqualTo(1);

		// A void and a credit are different acts and must never be filed under one another: an admin
		// arguing with a vendor a year later is asking which of the two happened.
		assertThat(audit("INVOICE_VOIDED", credited)).isZero();
		assertThat(audit("INVOICE_CREDITED", struck)).isZero();

		// The audit's after-state is read back from the row, so it reports what was stored.
		assertThat(admin.queryForObject("""
				SELECT after_state ->> 'status' FROM audit_events
				WHERE action = 'INVOICE_VOIDED' AND entity_id = ?
				""", String.class, struck)).isEqualTo("VOIDED");
	}

	// ---------------------------------------------------------------------
	// Why a reversal is a row and not a mark
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("the ledger refuses to be marked, which is why the correction is a second row")
	void theLedgerCannotBeMarked() throws Exception {
		UUID inv = invoice("INV-14", "500", null);
		UUID payment = pay(inv, "500", "UPI");

		// One connection, held open: the tenant setting is per-session, and an RLS policy that hides
		// the row would make this pass for the wrong reason — no rows matched rather than the trigger
		// refusing. The application role, too, because the trigger lets the schema owner through.
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
						POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute("SET app.tenant_id = '" + tenant + "'");
			try (java.sql.ResultSet visible = statement.executeQuery(
					"SELECT count(*) FROM invoice_payments WHERE id = '" + payment + "'")) {
				visible.next();
				assertThat(visible.getInt(1))
						.as("the row is visible to this session, so what follows is the trigger and not RLS")
						.isEqualTo(1);
			}

			assertThatThrownBy(() -> statement.executeUpdate(
					"UPDATE invoice_payments SET note = 'struck' WHERE id = '" + payment + "'"))
					.as("append-only: an UPDATE is refused, so no column could carry a void mark")
					.hasMessageContaining("append-only");
		}

		// And the shape it does accept is the one V40 designed: a negative amount.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM invoice_payments WHERE invoice_id = ? AND amount < 0",
				Integer.class, inv)).isZero();
		mvc.perform(reverse(inv, payment, "Bounced.")).andExpect(status().isNoContent());
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM invoice_payments WHERE invoice_id = ? AND amount < 0",
				Integer.class, inv)).isEqualTo(1);
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

	private UUID pay(UUID invoiceId, String amount, String method) throws Exception {
		String body = mvc.perform(payRequest(invoiceId, amount, method))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder payRequest(UUID invoiceId, String amount, String method) {
		return authed(post("/api/v1/vendor-invoices/{id}/payments", invoiceId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"paidOn\":\"" + java.time.LocalDate.now() + "\",\"amount\":" + amount
						+ ",\"method\":\"" + method + "\"}");
	}

	private MockHttpServletRequestBuilder reverse(UUID invoiceId, UUID paymentId, String reason) {
		return authed(post("/api/v1/vendor-invoices/{id}/payments/{paymentId}/reverse", invoiceId, paymentId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"" + reason + "\"}");
	}

	private MockHttpServletRequestBuilder voidInvoice(UUID invoiceId, String reason) {
		return authed(post("/api/v1/vendor-invoices/{id}/void", invoiceId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"" + reason + "\"}");
	}

	private MockHttpServletRequestBuilder credit(UUID invoiceId, String amount, String reason) {
		return authed(post("/api/v1/vendor-invoices/{id}/credit", invoiceId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":" + amount + ",\"reason\":\"" + reason + "\"}");
	}

	private String invoiceStatus(UUID invoiceId) {
		return admin.queryForObject("SELECT status FROM vendor_invoices WHERE id = ?", String.class, invoiceId);
	}

	private BigDecimal paidToDate(UUID invoiceId) {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE invoice_id = ?",
				BigDecimal.class, invoiceId);
	}

	private String rowJson(UUID paymentId) {
		return admin.queryForObject(
				"SELECT row_to_json(p)::text FROM invoice_payments p WHERE p.id = ?", String.class, paymentId);
	}

	private String invoiceJson(UUID invoiceId) {
		return admin.queryForObject(
				"SELECT row_to_json(vi)::text FROM vendor_invoices vi WHERE vi.id = ?", String.class, invoiceId);
	}

	/** Every payment row on the bill, in a fixed order, so two readings compare as one string. */
	private String paymentsJson(UUID invoiceId) {
		return admin.queryForObject("""
				SELECT COALESCE(json_agg(p ORDER BY p.created_at, p.id)::text, '[]')
				FROM invoice_payments p WHERE p.invoice_id = ?
				""", String.class, invoiceId);
	}

	private int audit(String action, UUID entityId) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ? AND entity_id = ?",
				Integer.class, action, entityId);
		return n == null ? 0 : n;
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}
}
