package org.iskcon.kms.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Paying an invoice with proof (T-272; R-PAY-1, R-PAY-2, R-PAY-3): what each method must carry, what
 * happens to a file that cannot be used, who may pay, and what the payment list gives back.
 *
 * <p>Against a real PostgreSQL, as the unprivileged application role, and through the real upload
 * endpoint: another temple's upload being refused is RLS hiding the row, and the "no payment without
 * its proof" guarantee is a transaction rolling back — both database behaviours a mock would only
 * assert, never prove.
 */
class PaymentProofIT extends AbstractIntegrationTest {

	private static final String PAYMENT_UPLOADS = "/api/v1/vendor-invoices/payment-uploads";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID invoiceA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = temple("radha-govinda", "Bengaluru Temple");
		templeB = temple("jagannath", "Mysuru Temple");
		UUID adminA = user(templeA, "uid-admin-a", "TEMPLE_ADMIN", "+919876500001");
		user(templeA, "uid-manager-a", "KITCHEN_MANAGER", "+919876500002");
		user(templeA, "uid-staff-a", "KITCHEN_STAFF", "+919876500003");
		user(templeB, "uid-admin-b", "TEMPLE_ADMIN", "+919876500004");
		invoiceA = invoice(templeA, vendor(templeA), adminA, "KVM-0917", "1030");
		signIn("uid-admin-a");
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

	// ---------------------------------------------------------------------------------------------
	// UPI, bank transfer, cheque: one proof
	// ---------------------------------------------------------------------------------------------

	@ParameterizedTest(name = "{0} without proof is a field error on proofAttachmentId, and nothing is saved")
	@ValueSource(strings = {"UPI", "BANK_TRANSFER", "CHEQUE"})
	void aNonCashPaymentWithoutProofIsRefused(String method) throws Exception {
		pay(invoiceA, body("600", method, Map.of()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("proofAttachmentId"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Proof of payment is required."));
		assertThat(paymentCount()).isZero();
	}

	@Test
	@DisplayName("UPI with proof is saved, and the proof is claimed onto that payment")
	void aUpiPaymentWithProofIsSavedAndClaimsIt() throws Exception {
		UUID proof = upload("PAYMENT_PROOF");

		UUID payment = created(pay(invoiceA, body("600", "UPI", Map.of("proofAttachmentId", proof))));

		assertThat(claimedBy(proof)).isEqualTo(payment);
		Map<String, Object> row = admin.queryForMap(
				"SELECT amount, method, received_by_name FROM invoice_payments WHERE id = ?", payment);
		assertThat(row.get("method")).isEqualTo("UPI");
		assertThat(row.get("received_by_name")).as("only cash has a receiver").isNull();
	}

	// ---------------------------------------------------------------------------------------------
	// Only more than ₹0 (T-280): a negative or zero amount is refused, with or without proof
	// ---------------------------------------------------------------------------------------------

	@ParameterizedTest(name = "{0} with no proof is KMS-400174, not a missing-file error, and nothing is written")
	@ValueSource(strings = {"-200", "-0.01", "0"})
	void aNotPositiveAmountWithoutProofIsRefused(String amount) throws Exception {
		// Without proof is the case that used to go through: a negative amount was asked for no files.
		// It has to be this code and not the missing-file error, because the amount is checked first
		// and telling someone to attach a receipt to a payment that would be refused anyway sends
		// them the wrong way.
		for (String method : List.of("UPI", "BANK_TRANSFER", "CHEQUE", "CASH")) {
			refusedAsNotPositive(pay(invoiceA, body(amount, method, Map.of())));
		}
		assertNothingWritten();
	}

	@ParameterizedTest(name = "{0} with complete proof is still KMS-400174, and the files stay unclaimed")
	@ValueSource(strings = {"-200", "-0.01", "0"})
	void aNotPositiveAmountWithProofIsRefused(String amount) throws Exception {
		UUID proof = upload("PAYMENT_PROOF");
		UUID note = upload("CASH_SIGNED_NOTE");
		UUID photo = upload("CASH_RECEIVER_PHOTO");

		for (String method : List.of("UPI", "BANK_TRANSFER", "CHEQUE")) {
			refusedAsNotPositive(pay(invoiceA, body(amount, method, Map.of("proofAttachmentId", proof))));
		}
		refusedAsNotPositive(pay(invoiceA, body(amount, "CASH", Map.of(
				"receivedByName", "Manjunath K.",
				"signedNoteAttachmentId", note,
				"receiverPhotoAttachmentId", photo))));

		assertNothingWritten();
		assertThat(claimedBy(proof)).isNull();
		assertThat(claimedBy(note)).isNull();
		assertThat(claimedBy(photo)).isNull();
	}

	@Test
	@DisplayName("a negative amount is refused before the invoice is read: a missing or voided bill gives the same answer")
	void theAmountIsCheckedBeforeTheInvoice() throws Exception {
		UUID proof = upload("PAYMENT_PROOF");
		refusedAsNotPositive(pay(UUID.randomUUID(), body("-200", "UPI", Map.of("proofAttachmentId", proof))));

		admin.update("""
				UPDATE vendor_invoices SET status = 'VOIDED', voided_at = now(), void_reason = 'Billed twice.',
					voided_by = created_by
				WHERE id = ?
				""", invoiceA);
		refusedAsNotPositive(pay(invoiceA, body("-200", "UPI", Map.of("proofAttachmentId", proof))));

		assertNothingWritten();
		assertThat(claimedBy(proof)).isNull();
	}

	@Test
	@DisplayName("a negative amount is refused even when it would not take paid-to-date below zero")
	void aNegativeAmountIsRefusedOnAPaidBillToo() throws Exception {
		// The case the old path accepted most readily: a bill with money on it, and a correction that
		// fits inside what was paid. Reverse is the way to undo it now.
		created(pay(invoiceA, body("600", "UPI", Map.of("proofAttachmentId", upload("PAYMENT_PROOF")))));
		UUID proof = upload("PAYMENT_PROOF");

		refusedAsNotPositive(pay(invoiceA, body("-200", "UPI", Map.of("proofAttachmentId", proof))));

		assertThat(paymentCount()).as("only the real payment").isEqualTo(1);
		assertThat(admin.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM invoice_payments", java.math.BigDecimal.class))
				.isEqualByComparingTo("600");
		assertThat(claimedBy(proof)).isNull();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INVOICE_PAYMENT_RECORDED'", Integer.class))
				.isEqualTo(1);
	}

	@ParameterizedTest(name = "{0} for a positive amount with no proof at all is refused, and nothing is written")
	@ValueSource(strings = {"UPI", "BANK_TRANSFER", "CHEQUE", "CASH"})
	void aPositivePaymentWithNoProofIsRefusedForEveryMethod(String method) throws Exception {
		pay(invoiceA, body("600", method, Map.of()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()))
				.andExpect(jsonPath("$.fieldErrors.length()").value(method.equals("CASH") ? 3 : 1));
		assertNothingWritten();
	}

	// ---------------------------------------------------------------------------------------------
	// Cash: a name and two photos
	// ---------------------------------------------------------------------------------------------

	@ParameterizedTest(name = "cash without {0} is exactly one field error, naming it")
	@ValueSource(strings = {"receivedByName", "signedNoteAttachmentId", "receiverPhotoAttachmentId"})
	void cashWithoutEachOfTheThreeIsOneFieldError(String left) throws Exception {
		Map<String, Object> cash = new LinkedHashMap<>(Map.of(
				"receivedByName", "Manjunath K.",
				"signedNoteAttachmentId", upload("CASH_SIGNED_NOTE"),
				"receiverPhotoAttachmentId", upload("CASH_RECEIVER_PHOTO")));
		cash.remove(left);

		String expected = switch (left) {
			case "receivedByName" -> "Received by is required.";
			case "signedNoteAttachmentId" -> "Signed note is required.";
			default -> "Photo of the person who took the cash is required.";
		};
		pay(invoiceA, body("600", "CASH", cash))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(left))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(expected));
		assertThat(paymentCount()).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments WHERE payment_id IS NOT NULL", Integer.class))
				.as("the two files that were sent stay unclaimed").isZero();
	}

	@Test
	@DisplayName("a blank receiver's name is as missing as none, and all three missing are named at once")
	void blankNameAndEverythingMissing() throws Exception {
		MvcResult result = pay(invoiceA, body("600", "CASH", Map.of("receivedByName", "   ")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()))
				.andReturn();
		assertThat(fieldsOf(result)).containsExactly(
				"receivedByName", "signedNoteAttachmentId", "receiverPhotoAttachmentId");
		assertThat(paymentCount()).isZero();
	}

	@Test
	@DisplayName("cash with all three is saved, the name stored trimmed, and both photos claimed")
	void cashWithAllThreeIsSaved() throws Exception {
		UUID note = upload("CASH_SIGNED_NOTE");
		UUID photo = upload("CASH_RECEIVER_PHOTO");

		UUID payment = created(pay(invoiceA, body("1030", "CASH", Map.of(
				"receivedByName", "  Manjunath K. ",
				"signedNoteAttachmentId", note,
				"receiverPhotoAttachmentId", photo))));

		assertThat(admin.queryForObject(
				"SELECT received_by_name FROM invoice_payments WHERE id = ?", String.class, payment))
				.isEqualTo("Manjunath K.");
		assertThat(claimedBy(note)).isEqualTo(payment);
		assertThat(claimedBy(photo)).isEqualTo(payment);
		assertThat(admin.queryForObject("SELECT status FROM vendor_invoices WHERE id = ?", String.class, invoiceA))
				.as("the rest of recording a payment is unchanged").isEqualTo("PAID");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM audit_events WHERE action = 'INVOICE_PAYMENT_RECORDED' AND entity_id = ?
				  AND after_state ->> 'receivedByName' = 'Manjunath K.'
				  AND jsonb_array_length(after_state -> 'attachmentIds') = 2
				""", Integer.class, invoiceA)).as("the audit entry names the receiver and both files").isEqualTo(1);
	}

	// ---------------------------------------------------------------------------------------------
	// A file that cannot be used
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a proof of the wrong kind is KMS-400167, and no payment is recorded")
	void aProofOfTheWrongKindIsRefused() throws Exception {
		UUID note = upload("CASH_SIGNED_NOTE");

		pay(invoiceA, body("600", "UPI", Map.of("proofAttachmentId", note)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(ErrorCode.ATTACHMENT_NOT_USABLE.reference()));
		assertThat(paymentCount()).isZero();
		assertThat(claimedBy(note)).isNull();
	}

	@Test
	@DisplayName("a proof already on another payment is KMS-400167, and no second payment is recorded")
	void aProofAlreadyUsedIsRefused() throws Exception {
		UUID proof = upload("PAYMENT_PROOF");
		UUID first = created(pay(invoiceA, body("300", "UPI", Map.of("proofAttachmentId", proof))));

		pay(invoiceA, body("300", "UPI", Map.of("proofAttachmentId", proof)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(ErrorCode.ATTACHMENT_NOT_USABLE.reference()));
		assertThat(paymentCount()).isEqualTo(1);
		assertThat(claimedBy(proof)).as("still on the first payment").isEqualTo(first);
	}

	@Test
	@DisplayName("when the second cash photo is refused, the first claim and the payment both roll back")
	void aRefusedSecondClaimTakesTheWholePaymentBack() throws Exception {
		UUID note = upload("CASH_SIGNED_NOTE");
		UUID notAPhoto = upload("PAYMENT_PROOF");

		pay(invoiceA, body("600", "CASH", Map.of(
				"receivedByName", "Manjunath K.",
				"signedNoteAttachmentId", note,
				"receiverPhotoAttachmentId", notAPhoto)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(ErrorCode.ATTACHMENT_NOT_USABLE.reference()));
		assertThat(paymentCount()).isZero();
		assertThat(claimedBy(note)).as("the note had been claimed before the photo failed; that is undone").isNull();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INVOICE_PAYMENT_RECORDED'", Integer.class))
				.isZero();
	}

	@Test
	@DisplayName("another temple's upload is refused as KMS-400167, and stays theirs and unclaimed")
	void anotherTemplesUploadIsRefused() throws Exception {
		signIn("uid-admin-b");
		UUID theirs = upload("PAYMENT_PROOF");

		signIn("uid-admin-a");
		pay(invoiceA, body("600", "UPI", Map.of("proofAttachmentId", theirs)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(ErrorCode.ATTACHMENT_NOT_USABLE.reference()));
		assertThat(paymentCount()).isZero();
		Map<String, Object> row = admin.queryForMap("SELECT tenant_id, payment_id FROM attachments WHERE id = ?", theirs);
		assertThat(row.get("tenant_id")).isEqualTo(templeB);
		assertThat(row.get("payment_id")).isNull();
	}

	@Test
	@DisplayName("fields of the other method are ignored: not stored, not claimed")
	void fieldsOfTheOtherMethodAreIgnored() throws Exception {
		UUID proof = upload("PAYMENT_PROOF");
		UUID strayNote = upload("CASH_SIGNED_NOTE");

		UUID payment = created(pay(invoiceA, body("600", "UPI", Map.of(
				"proofAttachmentId", proof,
				"receivedByName", "Manjunath K.",
				"signedNoteAttachmentId", strayNote))));

		assertThat(admin.queryForObject(
				"SELECT received_by_name FROM invoice_payments WHERE id = ?", String.class, payment)).isNull();
		assertThat(claimedBy(proof)).isEqualTo(payment);
		assertThat(claimedBy(strayNote)).isNull();

		// And the other way round: a proof left over from before the method was changed to cash.
		UUID strayProof = upload("PAYMENT_PROOF");
		UUID cash = created(pay(invoiceA, body("430", "CASH", Map.of(
				"receivedByName", "Manjunath K.",
				"signedNoteAttachmentId", upload("CASH_SIGNED_NOTE"),
				"receiverPhotoAttachmentId", upload("CASH_RECEIVER_PHOTO"),
				"proofAttachmentId", strayProof))));
		assertThat(claimedBy(strayProof)).isNull();
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments WHERE payment_id = ?", Integer.class, cash))
				.isEqualTo(2);
	}

	// ---------------------------------------------------------------------------------------------
	// The payment list (R-PAY-3)
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the list gives each payment its files and receiver, and a reversal none")
	void theListCarriesTheProof() throws Exception {
		UUID proof = upload("PAYMENT_PROOF");
		UUID upi = created(pay(invoiceA, body("500", "UPI", Map.of("proofAttachmentId", proof))));
		UUID note = upload("CASH_SIGNED_NOTE");
		UUID photo = upload("CASH_RECEIVER_PHOTO");
		UUID cash = created(pay(invoiceA, body("300", "CASH", Map.of(
				// The photo is sent first on purpose: the list's order is the screen's, not the request's.
				"receiverPhotoAttachmentId", photo,
				"signedNoteAttachmentId", note,
				"receivedByName", "Manjunath K."))));
		mvc.perform(authed(post("/api/v1/vendor-invoices/{i}/payments/{p}/reverse", invoiceA, upi))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Sent to the wrong account.\"}"))
				.andExpect(status().isNoContent());
		UUID reversal = admin.queryForObject("SELECT id FROM invoice_payments WHERE reverses = ?", UUID.class, upi);

		MvcResult result = mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/payments", invoiceA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andReturn();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> list = json.readValue(result.getResponse().getContentAsString(), List.class);
		Map<String, Map<String, Object>> byId = list.stream()
				.collect(Collectors.toMap(p -> (String) p.get("id"), p -> p));

		assertThat(byId.get(upi.toString()).keySet())
				.as("api.ts's InvoicePaymentView, field for field")
				.containsExactlyInAnyOrder("id", "paidOn", "amount", "method", "reference", "note",
						"recordedByName", "reverses", "reversedBy", "reverseReason", "receivedByName",
						"attachments", "createdAt");

		assertThat(ids(byId.get(upi.toString()))).containsExactly(proof.toString());
		assertThat(byId.get(upi.toString()).get("receivedByName")).isNull();
		assertThat(byId.get(upi.toString()).get("recordedByName")).isEqualTo("Test TEMPLE_ADMIN");

		assertThat(ids(byId.get(cash.toString()))).as("two for cash: the note, then the photo")
				.containsExactly(note.toString(), photo.toString());
		assertThat(byId.get(cash.toString()).get("receivedByName")).isEqualTo("Manjunath K.");
		@SuppressWarnings("unchecked")
		Map<String, Object> first = ((List<Map<String, Object>>) byId.get(cash.toString()).get("attachments")).get(0);
		assertThat(first.keySet()).as("api.ts's AttachmentView")
				.containsExactlyInAnyOrder("id", "kind", "contentType", "sizeBytes", "originalName", "uploadedAt");
		assertThat(first.get("kind")).isEqualTo("CASH_SIGNED_NOTE");

		assertThat(byId.get(reversal.toString()).get("attachments"))
				.as("a reversal carries no proof, and says so with an empty list rather than null")
				.isEqualTo(List.of());
	}

	@Test
	@DisplayName("a payment recorded before proof was asked for lists with no files and no receiver")
	void anOldPaymentListsEmpty() throws Exception {
		UUID old = admin.queryForObject("""
				INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by)
				VALUES (?, ?, CURRENT_DATE, 100, 'CASH', (SELECT id FROM users WHERE firebase_uid = 'uid-admin-a'))
				RETURNING id
				""", UUID.class, templeA, invoiceA);

		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/payments", invoiceA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(old.toString()))
				.andExpect(jsonPath("$[0].attachments.length()").value(0))
				.andExpect(jsonPath("$[0].receivedByName").value(org.hamcrest.Matchers.nullValue()));
	}

	// ---------------------------------------------------------------------------------------------
	// Who can pay (R-PAY-1): must not widen
	// ---------------------------------------------------------------------------------------------

	@ParameterizedTest(name = "{0} can neither record nor list payments")
	@ValueSource(strings = {"uid-manager-a", "uid-staff-a"})
	void kitchenRolesCannotPay(String uid) throws Exception {
		UUID proof = upload("PAYMENT_PROOF");

		signIn(uid);
		pay(invoiceA, body("600", "UPI", Map.of("proofAttachmentId", proof)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.NOT_PERMITTED.reference()));
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/payments", invoiceA)))
				.andExpect(status().isForbidden());
		assertThat(paymentCount()).isZero();
		assertThat(claimedBy(proof)).isNull();
	}

	@Test
	@DisplayName("the policy table still gives MANAGE_VENDOR_PAYMENTS to the Temple Admin and nobody else")
	void onlyTheTempleAdminHoldsThePaymentPermission() {
		assertThat(Arrays.stream(User.Role.values())
				.filter(role -> RolePermissions.has(role, Permission.MANAGE_VENDOR_PAYMENTS))
				.toList())
				.containsExactly(User.Role.TEMPLE_ADMIN);
	}

	// ---------------------------------------------------------------------------------------------
	// Fixtures
	// ---------------------------------------------------------------------------------------------

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenant, String uid, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, uid, "Test " + role, uid + "@example.com", phone, role);
	}

	private UUID vendor(UUID tenant) {
		return admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Kalasipalya Mandi', '+919812345678') RETURNING id",
				UUID.class, tenant);
	}

	private UUID invoice(UUID tenant, UUID vendor, UUID by, String number, String amount) {
		return admin.queryForObject("""
				INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, description, invoice_number,
					invoice_date, amount, status, created_by)
				VALUES (?, ?, true, 'vegetables', ?, CURRENT_DATE, ?::numeric, 'PENDING', ?) RETURNING id
				""", UUID.class, tenant, vendor, number, amount, by);
	}

	/** Uploads a small PNG through the real endpoint, as whoever is signed in, and returns its id. */
	private UUID upload(String kind) throws Exception {
		String body = mvc.perform(authed(multipart(PAYMENT_UPLOADS)
						.file(new MockMultipartFile("file", kind.toLowerCase() + ".png", "image/png", png()))
						.param("kind", kind)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString((String) json.readValue(body, Map.class).get("id"));
	}

	private String body(String amount, String method, Map<String, Object> extra) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("paidOn", java.time.LocalDate.now().toString());
		body.put("amount", new java.math.BigDecimal(amount));
		body.put("method", method);
		extra.forEach((k, v) -> body.put(k, v == null ? null : v.toString()));
		return json.writeValueAsString(body);
	}

	private ResultActions pay(UUID invoice, String body) throws Exception {
		return mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/payments", invoice))
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private UUID created(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString((String) json.readValue(body, Map.class).get("id"));
	}

	private UUID claimedBy(UUID attachment) {
		return admin.queryForObject("SELECT payment_id FROM attachments WHERE id = ?", UUID.class, attachment);
	}

	/** The refusal T-280 added, word for word, and a 400. */
	private static void refusedAsNotPositive(ResultActions result) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400174"))
				.andExpect(jsonPath("$.message").value("A payment has to be more than ₹0."))
				.andExpect(jsonPath("$.action").value(
						"Enter the amount paid. To undo a payment recorded by mistake, press Reverse beside it."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty());
	}

	/** No payment row, no claimed file, no audit entry: a refused payment leaves no trace. */
	private void assertNothingWritten() {
		assertThat(paymentCount()).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments WHERE payment_id IS NOT NULL", Integer.class))
				.isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
	}

	private int paymentCount() {
		Integer n = admin.queryForObject("SELECT count(*) FROM invoice_payments", Integer.class);
		return n == null ? 0 : n;
	}

	@SuppressWarnings("unchecked")
	private List<String> fieldsOf(MvcResult result) throws Exception {
		Map<String, Object> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
		return ((List<Map<String, Object>>) body.get("fieldErrors")).stream()
				.map(e -> (String) e.get("field")).toList();
	}

	@SuppressWarnings("unchecked")
	private static List<String> ids(Map<String, Object> payment) {
		return ((List<Map<String, Object>>) payment.get("attachments")).stream()
				.map(a -> (String) a.get("id")).toList();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer " + StubTokenVerifier.TOKEN);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	/** A PNG's signature and IHDR tag, then filler: enough for the upload's check, which reads the head. */
	private static byte[] png() {
		int[] head = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
		byte[] bytes = new byte[64];
		Arrays.fill(bytes, (byte) 0x2A);
		for (int i = 0; i < head.length; i++) {
			bytes[i] = (byte) head[i];
		}
		return bytes;
	}
}
