package org.iskcon.kms.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * The Invoices list's Unpaid filter means "money is still owed" for everyone (T-281).
 *
 * <p>Before this, a Temple Admin's Unpaid was built from {@code /api/v1/payables} (PENDING with
 * something left after credits and payments) while a Kitchen Manager's was {@code status=PENDING}, so
 * one label showed two sets of rows. {@code GET /api/v1/vendor-invoices?owed=true} is the one
 * definition both now use, and it lives in SQL in {@code VendorInvoiceService} while its twin lives in
 * Java in {@code InvoicePaymentService.payables()}. This test is what keeps the two twins in step: it
 * builds one of every kind of bill and asks both, invoice for invoice.
 *
 * <p>The set, all but two past due so the overdue filter has something to refuse:
 * <ul>
 *   <li>owed: unpaid; part-paid; part-credited; unpaid with no due date;</li>
 *   <li>not owed: paid in full; credited to nothing through the real endpoint (which flips it PAID);
 *       voided; and two legacy rows written by SQL that still say PENDING though nothing is left,
 *       one paid off by its payments and one credited to nothing. Those two are the rows the old
 *       {@code status=PENDING} answer showed a Kitchen Manager and payables never showed anyone;</li>
 *   <li>another temple's unpaid bill, which neither answer may see.</li>
 * </ul>
 */
@AutoConfigureMockMvc
class InvoiceListOwedIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID vendor;
	private UUID adminId;

	// Owed.
	private UUID unpaidLate;
	private UUID partPaidLate;
	private UUID partCreditedNotYetDue;
	private UUID unpaidNoDueDate;

	@BeforeEach
	void setUp() throws Exception {
		admin = new JdbcTemplate(adminDataSource());
		tenant = tenant("radha-govinda", "Bengaluru Temple");
		adminId = user(tenant, "uid-admin", "TEMPLE_ADMIN", "+919876500001");
		user(tenant, "uid-manager", "KITCHEN_MANAGER", "+919876500002");
		vendor = vendor(tenant);

		LocalDate today = LocalDate.now();
		// Ten days either side of today, so the server's CURRENT_DATE and the JVM's LocalDate.now()
		// (payables ages by the latter) cannot disagree about which side of the line a bill is on.
		String late = today.minusDays(10).toString();
		String longLate = today.minusDays(40).toString();
		String notYetDue = today.plusDays(10).toString();

		unpaidLate = invoice(tenant, vendor, adminId, "OWED-UNPAID", "1000", late, "PENDING");
		partPaidLate = invoice(tenant, vendor, adminId, "OWED-PART-PAID", "1000", longLate, "PENDING");
		payment(tenant, partPaidLate, adminId, "400");
		partCreditedNotYetDue = invoice(tenant, vendor, adminId, "OWED-PART-CREDITED", "1000", notYetDue, "PENDING");
		admin.update("UPDATE vendor_invoices SET credited_amount = 250 WHERE id = ?", partCreditedNotYetDue);
		unpaidNoDueDate = invoice(tenant, vendor, adminId, "OWED-NO-DUE-DATE", "300", null, "PENDING");

		UUID paid = invoice(tenant, vendor, adminId, "NOT-OWED-PAID", "500", late, "PAID");
		payment(tenant, paid, adminId, "500");

		// Through the real credit endpoint, as a Temple Admin would do it: the service restates the
		// status, so it comes out PAID.
		UUID creditedToZero = invoice(tenant, vendor, adminId, "NOT-OWED-CREDITED", "700", late, "PENDING");
		signIn("uid-admin");
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/credit", creditedToZero))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":700,\"reason\":\"Whole delivery returned\"}"))
				.andExpect(status().isNoContent());
		assertThat(statusOf(creditedToZero)).isEqualTo("PAID");

		UUID voided = invoice(tenant, vendor, adminId, "NOT-OWED-VOIDED", "800", late, "VOIDED");
		assertThat(voided).isNotNull();

		// Legacy: PENDING in the column, nothing left in fact. Written by SQL because the service would
		// never leave a row like this, which is exactly why only the sum can be trusted.
		UUID legacyPaidOff = invoice(tenant, vendor, adminId, "LEGACY-PAID-OFF", "600", late, "PENDING");
		payment(tenant, legacyPaidOff, adminId, "600");
		UUID legacyCredited = invoice(tenant, vendor, adminId, "LEGACY-CREDITED", "450", longLate, "PENDING");
		admin.update("UPDATE vendor_invoices SET credited_amount = amount WHERE id = ?", legacyCredited);
		assertThat(statusOf(legacyPaidOff)).isEqualTo("PENDING");
		assertThat(statusOf(legacyCredited)).isEqualTo("PENDING");

		// Another temple's bill, owed and late. Row-level security keeps it out of both answers.
		UUID otherTenant = tenant("iskcon-mysore", "Mysore Temple");
		UUID otherAdmin = user(otherTenant, "uid-other-admin", "TEMPLE_ADMIN", "+919876500003");
		invoice(otherTenant, vendor(otherTenant), otherAdmin, "OTHER-TEMPLE", "900", late, "PENDING");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM attachments");
		admin.execute("DELETE FROM invoice_payments");
		admin.execute("DELETE FROM vendor_invoices");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("owed=true returns exactly the invoices payables lists, to a Temple Admin and to a Kitchen Manager")
	void owedMatchesPayablesForEveryReader() throws Exception {
		signIn("uid-admin");
		Set<String> payables = ids(get("/api/v1/payables"), "$[*].invoiceId");

		// Pinned to the named set first, so the comparison below cannot pass by both sides being empty
		// or both being wrong in the same way.
		assertThat(payables).as("payables: what the temple owes")
				.containsExactlyInAnyOrder(str(unpaidLate), str(partPaidLate), str(partCreditedNotYetDue),
						str(unpaidNoDueDate));

		assertThat(ids(get("/api/v1/vendor-invoices").param("owed", "true"), "$[*].id"))
				.as("Temple Admin's Unpaid").isEqualTo(payables);

		// The Kitchen Manager cannot read payables, which is why this filter exists on the list.
		signIn("uid-manager");
		mvc.perform(authed(get("/api/v1/payables"))).andExpect(status().isForbidden());
		assertThat(ids(get("/api/v1/vendor-invoices").param("owed", "true"), "$[*].id"))
				.as("Kitchen Manager's Unpaid is the same rows").isEqualTo(payables);
	}

	@Test
	@DisplayName("overdue=true is past due AND still owed: no paid-off, credited-off or voided bill, for either reader")
	void overdueExcludesNothingOwed() throws Exception {
		signIn("uid-admin");
		String today = LocalDate.now().toString();
		List<java.util.Map<String, Object>> payables = JsonPath.read(body(get("/api/v1/payables")), "$[*]");
		// ISO dates compare correctly as strings. No due date means never late.
		Set<String> owedAndLate = payables.stream()
				.filter(p -> p.get("dueDate") != null && ((String) p.get("dueDate")).compareTo(today) < 0)
				.map(p -> (String) p.get("invoiceId"))
				.collect(Collectors.toSet());
		assertThat(owedAndLate).as("payables past due").containsExactlyInAnyOrder(str(unpaidLate), str(partPaidLate));

		assertThat(ids(get("/api/v1/vendor-invoices").param("overdue", "true"), "$[*].id"))
				.as("Temple Admin's overdue").isEqualTo(owedAndLate);

		signIn("uid-manager");
		assertThat(ids(get("/api/v1/vendor-invoices").param("overdue", "true"), "$[*].id"))
				.as("Kitchen Manager's Overdue").isEqualTo(owedAndLate);
	}

	@Test
	@DisplayName("without owed, the list and its status filter are unchanged: status=PENDING still includes the legacy rows")
	void withoutOwedNothingChanges() throws Exception {
		signIn("uid-manager");
		assertThat(ids(get("/api/v1/vendor-invoices"), "$[*].id")).as("All: every invoice of this temple").hasSize(9);
		assertThat(ids(get("/api/v1/vendor-invoices").param("status", "PENDING"), "$[*].invoiceNumber"))
				.containsExactlyInAnyOrder("OWED-UNPAID", "OWED-PART-PAID", "OWED-PART-CREDITED", "OWED-NO-DUE-DATE",
						"LEGACY-PAID-OFF", "LEGACY-CREDITED");
	}

	// ---------------------------------------------------------------------

	private Set<String> ids(MockHttpServletRequestBuilder request, String path) throws Exception {
		List<String> ids = JsonPath.read(body(request), path);
		return ids.stream().collect(Collectors.toSet());
	}

	private String body(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(authed(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static String str(UUID id) {
		return id.toString();
	}

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenantId, String uid, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, role, uid + "@example.com", phone, role);
	}

	private UUID vendor(UUID tenantId) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenantId);
	}

	/** A direct invoice in the given status; a VOIDED one carries the mark its CHECK requires. */
	private UUID invoice(UUID tenantId, UUID vendorId, UUID by, String number, String amount, String dueDate,
			String status) {
		boolean voided = status.equals("VOIDED");
		return admin.queryForObject("""
				INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, description, invoice_number,
					invoice_date, amount, due_date, status, created_by, voided_at, voided_by, void_reason)
				VALUES (?, ?, true, 'cash market', ?, CURRENT_DATE, ?::numeric, ?::date, ?, ?,
					CASE WHEN ? THEN now() END, CASE WHEN ? THEN ?::uuid END, CASE WHEN ? THEN 'Raised in error' END)
				RETURNING id
				""", UUID.class, tenantId, vendorId, number, amount, dueDate, status, by,
				voided, voided, by, voided);
	}

	private void payment(UUID tenantId, UUID invoiceId, UUID by, String amount) {
		admin.update("""
				INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by)
				VALUES (?, ?, CURRENT_DATE, ?::numeric, 'UPI', ?)
				""", tenantId, invoiceId, amount, by);
	}

	private String statusOf(UUID invoiceId) {
		return admin.queryForObject("SELECT status FROM vendor_invoices WHERE id = ?", String.class, invoiceId);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
