package org.iskcon.kms.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Uploads (T-267): storing the copy of a bill and proof of payment, claiming them onto an invoice or
 * a payment, and reading them back — against a real PostgreSQL, as the unprivileged application
 * role, so that what RLS hides from another temple is proved by the database and not by a mock.
 *
 * <p>Most requests go through MockMvc. The two size tests go over real HTTP to the running server
 * instead, because MockMvc hands the controller a file without ever parsing a multipart body, so it
 * cannot show whether {@code spring.servlet.multipart}'s limits let a slightly-over file through to
 * the service or stop a much larger one in the container.
 */
class AttachmentIT extends AbstractIntegrationTest {

	private static final String BILL_UPLOADS = "/api/v1/vendor-invoices/bill-uploads";
	private static final String PAYMENT_UPLOADS = "/api/v1/vendor-invoices/payment-uploads";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private AttachmentService attachments;

	/** The application's own JdbcTemplate: kms_app, through TenantAwareDataSource, under RLS. */
	@Autowired
	private JdbcTemplate appJdbc;

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private ObjectMapper json;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;
	private UUID adminA;
	private UUID managerA;
	private UUID adminB;
	private UUID invoiceA;
	private UUID otherInvoiceA;
	private UUID invoiceB;
	private UUID paymentA;
	private UUID cashPaymentA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = temple("radha-govinda", "Bengaluru Temple");
		templeB = temple("jagannath", "Mysuru Temple");
		adminA = user(templeA, "uid-admin-a", "TEMPLE_ADMIN", "+919876500001");
		managerA = user(templeA, "uid-manager-a", "KITCHEN_MANAGER", "+919876500002");
		user(templeA, "uid-volunteer-a", "VOLUNTEER", "+919876500003");
		adminB = user(templeB, "uid-admin-b", "TEMPLE_ADMIN", "+919876500004");

		UUID vendorA = vendor(templeA);
		UUID vendorB = vendor(templeB);
		invoiceA = invoice(templeA, vendorA, adminA, "KVM-0917");
		otherInvoiceA = invoice(templeA, vendorA, adminA, "KVM-0918");
		invoiceB = invoice(templeB, vendorB, adminB, "MYS-0001");
		paymentA = payment(templeA, invoiceA, adminA, "UPI");
		cashPaymentA = payment(templeA, invoiceA, adminA, "CASH");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		// attachments first: its foreign keys to invoices and payments are RESTRICT, and every other
		// class's teardown deletes those without knowing this table exists.
		admin.execute("DELETE FROM attachments");
		admin.execute("DELETE FROM invoice_payments");
		admin.execute("DELETE FROM vendor_invoices");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---------------------------------------------------------------------------------------------
	// Who may upload and fetch
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a Kitchen Manager can upload a bill, and the answer is exactly the AttachmentView")
	void kitchenManagerUploadsABill() throws Exception {
		signIn("uid-manager-a");

		MvcResult result = mvc.perform(authed(multipart(BILL_UPLOADS)
						.file(new MockMultipartFile("file", "KVM-0917.png", "image/png", png(64)))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("INVOICE_BILL"))
				.andExpect(jsonPath("$.contentType").value("image/png"))
				.andExpect(jsonPath("$.sizeBytes").value(64))
				.andExpect(jsonPath("$.originalName").value("KVM-0917.png"))
				.andReturn();

		@SuppressWarnings("unchecked")
		Map<String, Object> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
		assertThat(body.keySet())
				.as("api.ts's AttachmentView, and nothing else — no storage key")
				.containsExactlyInAnyOrder("id", "kind", "contentType", "sizeBytes", "originalName", "uploadedAt");
		assertThat(body.get("uploadedAt")).isInstanceOf(String.class);

		UUID id = UUID.fromString((String) body.get("id"));
		Map<String, Object> row = admin.queryForMap(
				"SELECT tenant_id, uploaded_by, storage_key, invoice_id, payment_id FROM attachments WHERE id = ?", id);
		assertThat(row.get("tenant_id")).as("the temple comes from the token").isEqualTo(templeA);
		assertThat(row.get("uploaded_by")).as("the signed-in user").isEqualTo(managerA);
		assertThat((String) row.get("storage_key")).isEqualTo("tenants/" + templeA + "/attachments/" + id);
		assertThat(row.get("invoice_id")).as("unclaimed until the invoice is saved").isNull();
		assertThat(row.get("payment_id")).isNull();
	}

	@Test
	@DisplayName("someone without MANAGE_PURCHASE_ORDERS cannot upload or fetch a bill")
	void aVolunteerCannotTouchBills() throws Exception {
		signIn("uid-volunteer-a");

		mvc.perform(authed(multipart(BILL_UPLOADS).file(pngFile("bill.png"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.NOT_PERMITTED.reference()));
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/bill", invoiceA)))
				.andExpect(status().isForbidden());
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments", Integer.class)).isZero();
	}

	@Test
	@DisplayName("only a Temple Admin can upload payment proof: a Kitchen Manager is refused")
	void onlyTheTempleAdminUploadsPaymentProof() throws Exception {
		signIn("uid-manager-a");
		mvc.perform(authed(multipart(PAYMENT_UPLOADS).file(pngFile("upi.png")).param("kind", "PAYMENT_PROOF")))
				.andExpect(status().isForbidden());

		signIn("uid-admin-a");
		mvc.perform(authed(multipart(PAYMENT_UPLOADS).file(pngFile("upi.png")).param("kind", "PAYMENT_PROOF")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("PAYMENT_PROOF"));
		mvc.perform(authed(multipart(PAYMENT_UPLOADS).file(pngFile("note.png")).param("kind", "CASH_SIGNED_NOTE")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("CASH_SIGNED_NOTE"));
		mvc.perform(authed(multipart(PAYMENT_UPLOADS).file(pngFile("id.png")).param("kind", "CASH_RECEIVER_PHOTO")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("CASH_RECEIVER_PHOTO"));
	}

	@Test
	@DisplayName("a payment file is fetched only by a Temple Admin, and only on its own payment and invoice")
	void aPaymentFileIsFetchedOnlyWhereItBelongs() throws Exception {
		signIn("uid-admin-a");
		byte[] bytes = jpeg(300);
		UUID proof = upload(PAYMENT_UPLOADS + "?kind=PAYMENT_PROOF", "UPI-0917.jpg", "image/jpeg", bytes);
		asTemple(templeA, () -> attachments.claimForPayment(proof, AttachmentKind.PAYMENT_PROOF, paymentA));

		byte[] served = mvc.perform(authed(get(paymentFile(invoiceA, paymentA, proof))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(served).isEqualTo(bytes);

		// Every mixed-up address finds nothing rather than somebody else's proof.
		mvc.perform(authed(get(paymentFile(otherInvoiceA, paymentA, proof)))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.reference()));
		mvc.perform(authed(get(paymentFile(invoiceA, cashPaymentA, proof)))).andExpect(status().isNotFound());
		mvc.perform(authed(get(paymentFile(invoiceA, paymentA, UUID.randomUUID())))).andExpect(status().isNotFound());

		signIn("uid-manager-a");
		mvc.perform(authed(get(paymentFile(invoiceA, paymentA, proof)))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------------------------------
	// What is accepted: the bytes decide
	// ---------------------------------------------------------------------------------------------

	@ParameterizedTest(name = "{0} is stored as {1}")
	@CsvSource({
			"jpeg, image/jpeg",
			"png, image/png",
			"webp, image/webp",
			"heic, image/heic",
			"heif, image/heif",
			"pdf, application/pdf"})
	@DisplayName("each of the five accepted kinds of file is recognised from its bytes")
	void eachAcceptedTypeIsRecognised(String sample, String expected) throws Exception {
		signIn("uid-manager-a");
		// Declared as octet-stream and named with no extension: nothing but the bytes to go on.
		mvc.perform(authed(multipart(BILL_UPLOADS)
						.file(new MockMultipartFile("file", "scan", "application/octet-stream", sample(sample)))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value(expected));
	}

	@Test
	@DisplayName("a PNG renamed .pdf and declared as a PDF is stored, and served, as a PNG")
	void aRenamedPngIsJudgedByItsBytes() throws Exception {
		signIn("uid-manager-a");
		UUID id = upload(BILL_UPLOADS, "bill.pdf", "application/pdf", png(128));

		assertThat(admin.queryForObject("SELECT content_type FROM attachments WHERE id = ?", String.class, id))
				.isEqualTo("image/png");
		asTemple(templeA, () -> attachments.claimBill(id, invoiceA));
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/bill", invoiceA)))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/png"));
	}

	@Test
	@DisplayName("an EXE is KMS-400165, whatever it claims to be")
	void anExecutableIsRefused() throws Exception {
		signIn("uid-manager-a");
		byte[] exe = new byte[256];
		exe[0] = 'M';
		exe[1] = 'Z';
		mvc.perform(authed(multipart(BILL_UPLOADS).file(new MockMultipartFile("file", "bill.pdf", "application/pdf", exe))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400165"));
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments", Integer.class)).isZero();
	}

	@Test
	@DisplayName("text dressed as a photo, and an AVIF, are refused too")
	void lookalikesAreRefused() throws Exception {
		signIn("uid-manager-a");
		mvc.perform(authed(multipart(BILL_UPLOADS).file(new MockMultipartFile("file", "bill.jpg", "image/jpeg",
						"not really a photo".getBytes(StandardCharsets.UTF_8)))))
				.andExpect(jsonPath("$.code").value("KMS-400165"));
		mvc.perform(authed(multipart(BILL_UPLOADS).file(new MockMultipartFile("file", "bill.avif", "image/avif",
						isoBrand("avif")))))
				.andExpect(jsonPath("$.code").value("KMS-400165"));
	}

	@Test
	@DisplayName("an empty file, or no file at all, is a validation error naming the file")
	void anEmptyUploadIsAValidationError() throws Exception {
		signIn("uid-manager-a");
		mvc.perform(authed(multipart(BILL_UPLOADS).file(new MockMultipartFile("file", "bill.png", "image/png", new byte[0]))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("file"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Choose a file to upload."));
		mvc.perform(authed(multipart(BILL_UPLOADS)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
	}

	@Test
	@DisplayName("payment-uploads refuses INVOICE_BILL and an unknown kind, and lists the three it takes")
	void paymentUploadsTakesOnlyPaymentKinds() throws Exception {
		signIn("uid-admin-a");
		for (String kind : List.of("INVOICE_BILL", "RECEIPT", "payment_proof")) {
			mvc.perform(authed(multipart(PAYMENT_UPLOADS).file(pngFile("x.png")).param("kind", kind)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400001"))
					.andExpect(jsonPath("$.fieldErrors[0].field").value("kind"))
					.andExpect(jsonPath("$.fieldErrors[0].message")
							.value("Choose one of PAYMENT_PROOF, CASH_SIGNED_NOTE, CASH_RECEIVER_PHOTO."));
		}
		mvc.perform(authed(multipart(PAYMENT_UPLOADS).file(pngFile("x.png"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("kind"));
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments", Integer.class)).isZero();
	}

	@Test
	@DisplayName("a name sent with a path keeps only the file's own name")
	void aPathInTheNameIsDropped() throws Exception {
		signIn("uid-manager-a");
		mvc.perform(authed(multipart(BILL_UPLOADS)
						.file(new MockMultipartFile("file", "C:\\fakepath\\KVM-0917.png", "image/png", png(32)))))
				.andExpect(jsonPath("$.originalName").value("KVM-0917.png"));
	}

	// ---------------------------------------------------------------------------------------------
	// Size, over real HTTP
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("over real HTTP: exactly 10 MB is stored, and 10 MB and one byte is KMS-400166 from the service")
	void tenMegabytesIsTheLimit() throws Exception {
		signIn("uid-manager-a");

		ResponseEntity<String> atLimit = postOverHttp(png((int) AttachmentService.MAX_BYTES));
		assertThat(atLimit.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> over = postOverHttp(png((int) AttachmentService.MAX_BYTES + 1));
		assertThat(over.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
		assertThat(over.getBody()).contains("\"code\":\"KMS-400166\"");
		assertThat(admin.queryForObject("SELECT count(*) FROM attachments", Integer.class))
				.as("only the file at the limit was kept").isEqualTo(1);
	}

	@Test
	@DisplayName("over real HTTP: a 20 MB photo is refused by the service, cleanly, not by the container")
	void aMuchLargerPhotoStillReachesTheService() {
		signIn("uid-manager-a");
		ResponseEntity<String> response = postOverHttp(png(20 * 1024 * 1024));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
		assertThat(response.getBody()).contains("\"code\":\"KMS-400166\"");
	}

	/**
	 * Past the multipart limit (32 MB) the container refuses while it is still reading the request.
	 *
	 * <p>Sent by hand over a socket, and deliberately only the headers: a client that goes on writing
	 * a body the server has stopped reading gets a broken pipe before it can read the answer (the
	 * first version of this test, using TestRestTemplate and an 11.5 MB file against an 11 MB limit,
	 * failed exactly that way while the server logged KMS-400166). Declaring 40 MB and sending none of
	 * it isolates the one thing under test: what the server answers when the limit trips.
	 */
	@Test
	@DisplayName("over real HTTP: a request past the container's limit is KMS-400166 as well, not KMS-500001")
	void aRequestPastTheContainersLimitIsStillTooLarge() throws Exception {
		signIn("uid-manager-a");
		String boundary = "kms-t267";
		try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
			socket.setSoTimeout(15_000);
			String head = "POST " + BILL_UPLOADS + " HTTP/1.1\r\n"
					+ "Host: localhost:" + port + "\r\n"
					+ "Authorization: Bearer " + StubTokenVerifier.TOKEN + "\r\n"
					+ "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
					+ "Content-Length: " + (40L * 1024 * 1024) + "\r\n"
					+ "Connection: close\r\n\r\n";
			socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();

			// Read what has arrived until the error body is complete. Not to end of stream: the
			// container may hold the connection open after answering, so waiting for EOF would wait
			// out the timeout with the answer already in hand.
			StringBuilder received = new StringBuilder();
			byte[] chunk = new byte[8192];
			java.io.InputStream in = socket.getInputStream();
			while (!(received.indexOf("\"fieldErrors\":[") >= 0
					&& received.indexOf("]}", received.indexOf("\"fieldErrors\":[")) >= 0)) {
				int n = in.read(chunk);
				if (n < 0) {
					break;
				}
				received.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
			}
			String answer = received.toString();
			assertThat(answer).startsWith("HTTP/1.1 413");
			assertThat(answer).contains("\"code\":\"KMS-400166\"").doesNotContain("500001");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Claims
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a claimed bill streams back the very same bytes, inline, under the name it was sent with")
	void theBillStreamsBackTheSameBytes() throws Exception {
		signIn("uid-manager-a");
		byte[] bytes = pdf(5000);
		UUID id = upload(BILL_UPLOADS, "Kalasipalya bill 17 Sept.pdf", "application/pdf", bytes);

		AttachmentView claimed = asTemple(templeA, () -> attachments.claimBill(id, invoiceA));
		assertThat(claimed.id()).isEqualTo(id);
		assertThat(asTemple(templeA, () -> attachments.billOf(invoiceA))).contains(claimed);

		MvcResult result = mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/bill", invoiceA)))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
		assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
		ContentDisposition disposition = ContentDisposition.parse(
				result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
		assertThat(disposition.isInline()).isTrue();
		assertThat(disposition.getFilename()).isEqualTo("Kalasipalya bill 17 Sept.pdf");
	}

	@Test
	@DisplayName("an invoice with no bill is the ordinary not-found, not a fault")
	void anInvoiceWithNoBillIsNotFound() throws Exception {
		signIn("uid-manager-a");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/bill", invoiceA)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.reference()));
		assertThat(asTemple(templeA, () -> attachments.billOf(invoiceA))).isEmpty();
	}

	@Test
	@DisplayName("claiming an upload twice is KMS-400167, and so is a second bill on one invoice")
	void aClaimIsOnceOnly() throws Exception {
		signIn("uid-manager-a");
		UUID first = upload(BILL_UPLOADS, "a.png", "image/png", png(40));
		UUID second = upload(BILL_UPLOADS, "b.png", "image/png", png(41));

		asTemple(templeA, () -> attachments.claimBill(first, invoiceA));

		assertNotUsable(() -> attachments.claimBill(first, invoiceA));
		assertNotUsable(() -> attachments.claimBill(first, otherInvoiceA));
		assertNotUsable(() -> attachments.claimBill(second, invoiceA));
		assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, first))
				.isEqualTo(invoiceA);
		assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, second))
				.as("the refused claim changed nothing").isNull();
	}

	@Test
	@DisplayName("claiming an upload as the wrong kind is KMS-400167, both ways and between payment kinds")
	void aClaimOfTheWrongKindIsRefused() throws Exception {
		signIn("uid-manager-a");
		UUID bill = upload(BILL_UPLOADS, "bill.png", "image/png", png(40));
		signIn("uid-admin-a");
		UUID proof = upload(PAYMENT_UPLOADS + "?kind=PAYMENT_PROOF", "upi.png", "image/png", png(40));
		UUID note = upload(PAYMENT_UPLOADS + "?kind=CASH_SIGNED_NOTE", "note.png", "image/png", png(40));

		assertNotUsable(() -> attachments.claimForPayment(bill, AttachmentKind.PAYMENT_PROOF, paymentA));
		assertNotUsable(() -> attachments.claimBill(proof, invoiceA));
		assertNotUsable(() -> attachments.claimForPayment(note, AttachmentKind.CASH_RECEIVER_PHOTO, cashPaymentA));
		assertNotUsable(() -> attachments.claimForPayment(null, AttachmentKind.PAYMENT_PROOF, paymentA));
		assertNotUsable(() -> attachments.claimForPayment(UUID.randomUUID(), AttachmentKind.PAYMENT_PROOF, paymentA));
		assertThatThrownBy(() -> asTemple(templeA,
				() -> attachments.claimForPayment(proof, AttachmentKind.INVOICE_BILL, paymentA)))
				.as("asking for a bill on a payment is the calling code's mistake")
				.isInstanceOf(IllegalArgumentException.class);

		// And the right kinds go through.
		asTemple(templeA, () -> attachments.claimForPayment(proof, AttachmentKind.PAYMENT_PROOF, paymentA));
		asTemple(templeA, () -> attachments.claimForPayment(note, AttachmentKind.CASH_SIGNED_NOTE, cashPaymentA));
	}

	@Test
	@DisplayName("the files of many payments come back in one call, in screen order, with every payment keyed")
	void theFilesOfManyPayments() throws Exception {
		signIn("uid-admin-a");
		UUID photo = upload(PAYMENT_UPLOADS + "?kind=CASH_RECEIVER_PHOTO", "id.jpg", "image/jpeg", jpeg(50));
		UUID note = upload(PAYMENT_UPLOADS + "?kind=CASH_SIGNED_NOTE", "note.jpg", "image/jpeg", jpeg(50));
		asTemple(templeA, () -> attachments.claimForPayment(photo, AttachmentKind.CASH_RECEIVER_PHOTO, cashPaymentA));
		asTemple(templeA, () -> attachments.claimForPayment(note, AttachmentKind.CASH_SIGNED_NOTE, cashPaymentA));

		Map<UUID, List<AttachmentView>> files = asTemple(templeA,
				() -> attachments.ofPayments(List.of(paymentA, cashPaymentA)));

		assertThat(files).containsOnlyKeys(paymentA, cashPaymentA);
		assertThat(files.get(paymentA)).isEmpty();
		assertThat(files.get(cashPaymentA)).extracting(AttachmentView::id).containsExactly(note, photo);
		assertThat(asTemple(templeA, () -> attachments.ofPayments(List.of()))).isEmpty();
	}

	// ---------------------------------------------------------------------------------------------
	// Another temple
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("another temple can neither see, fetch nor claim an upload, and cannot be claimed onto")
	void anotherTempleIsShutOut() throws Exception {
		signIn("uid-manager-a");
		UUID bill = upload(BILL_UPLOADS, "bill.png", "image/png", png(40));
		UUID spare = upload(BILL_UPLOADS, "spare.png", "image/png", png(40));
		asTemple(templeA, () -> attachments.claimBill(bill, invoiceA));

		// The database itself, as kms_app with temple B's context: the row is not there.
		assertThat(asTemple(templeB, () -> appJdbc.queryForObject(
				"SELECT count(*) FROM attachments WHERE id IN (?, ?)", Integer.class, bill, spare)))
				.isZero();
		assertThat(asTemple(templeA, () -> appJdbc.queryForObject(
				"SELECT count(*) FROM attachments WHERE id IN (?, ?)", Integer.class, bill, spare)))
				.as("and temple A does see both, so the zero above is RLS and not an empty table")
				.isEqualTo(2);

		// Temple B's admin, over HTTP: temple A's bill is not found.
		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}/bill", invoiceA)))
				.andExpect(status().isNotFound());

		// Temple B claiming temple A's unclaimed upload onto its own invoice: to B it does not exist.
		assertNotUsable(templeB, () -> attachments.claimBill(spare, invoiceB));

		// Temple A claiming its own upload onto temple B's invoice. The foreign key alone would allow
		// it — PostgreSQL checks it past RLS — so this is the parent check earning its place.
		assertThatThrownBy(() -> asTemple(templeA, () -> attachments.claimBill(spare, invoiceB)))
				.isInstanceOfSatisfying(ApplicationException.class,
						e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
		assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, spare)).isNull();
	}

	@Test
	@DisplayName("the application role holds UPDATE on attachments, which every claim needs")
	void theApplicationRoleMayClaim() {
		assertThat(admin.queryForObject(
				"SELECT has_table_privilege(?, 'attachments', 'UPDATE')", Boolean.class, APP_ROLE)).isTrue();
		assertThat(admin.queryForObject(
				"SELECT has_table_privilege(?, 'attachments', 'INSERT')", Boolean.class, APP_ROLE)).isTrue();
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

	private UUID invoice(UUID tenant, UUID vendor, UUID by, String number) {
		return admin.queryForObject("""
				INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, description, invoice_number,
					invoice_date, amount, status, created_by)
				VALUES (?, ?, true, 'vegetables', ?, CURRENT_DATE, 1030, 'PENDING', ?) RETURNING id
				""", UUID.class, tenant, vendor, number, by);
	}

	private UUID payment(UUID tenant, UUID invoice, UUID by, String method) {
		return admin.queryForObject("""
				INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by)
				VALUES (?, ?, CURRENT_DATE, 100, ?, ?) RETURNING id
				""", UUID.class, tenant, invoice, method, by);
	}

	private UUID upload(String path, String name, String declaredType, byte[] bytes) throws Exception {
		String[] parts = path.split("\\?kind=");
		MockHttpServletRequestBuilder request = multipart(parts[0])
				.file(new MockMultipartFile("file", name, declaredType, bytes));
		if (parts.length == 2) {
			request = request.param("kind", parts[1]);
		}
		String body = mvc.perform(authed(request))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString((String) json.readValue(body, Map.class).get("id"));
	}

	private ResponseEntity<String> postOverHttp(byte[] bytes) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(StubTokenVerifier.TOKEN);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("file", new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return "big.png";
			}
		});
		return rest.exchange("http://localhost:" + port + BILL_UPLOADS, HttpMethod.POST,
				new HttpEntity<>(form, headers), String.class);
	}

	private static String paymentFile(UUID invoice, UUID payment, UUID attachment) {
		return "/api/v1/vendor-invoices/" + invoice + "/payments/" + payment + "/attachments/" + attachment;
	}

	private void assertNotUsable(Supplier<?> claim) {
		assertNotUsable(templeA, claim);
	}

	private void assertNotUsable(UUID temple, Supplier<?> claim) {
		assertThatThrownBy(() -> asTemple(temple, claim))
				.isInstanceOfSatisfying(ApplicationException.class,
						e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ATTACHMENT_NOT_USABLE));
	}

	/**
	 * Runs a service call as the invoice or payment service will: inside a request that has a temple.
	 * The service opens its own transaction, so its connection is checked out with this temple set.
	 */
	private <T> T asTemple(UUID temple, Supplier<T> call) {
		TenantContext.set(temple);
		try {
			return call.get();
		} finally {
			TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer " + StubTokenVerifier.TOKEN);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private static MockMultipartFile pngFile(String name) {
		return new MockMultipartFile("file", name, "image/png", png(64));
	}

	// Samples: each format's own signature, then filler. Enough for the check, which reads the head.

	private static byte[] sample(String kind) {
		return switch (kind) {
			case "jpeg" -> jpeg(200);
			case "png" -> png(200);
			case "webp" -> withHead(200, 'R', 'I', 'F', 'F', 0xC0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ');
			case "heic" -> isoBrand("heic");
			case "heif" -> isoBrand("mif1");
			case "pdf" -> pdf(200);
			default -> throw new IllegalArgumentException(kind);
		};
	}

	static byte[] png(int size) {
		return withHead(size, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R');
	}

	static byte[] jpeg(int size) {
		return withHead(size, 0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0);
	}

	static byte[] pdf(int size) {
		return withHead(size, '%', 'P', 'D', 'F', '-', '1', '.', '7', '\n');
	}

	static byte[] isoBrand(String brand) {
		byte[] b = brand.getBytes(StandardCharsets.US_ASCII);
		return withHead(200, 0, 0, 0, 0x18, 'f', 't', 'y', 'p', b[0], b[1], b[2], b[3], 0, 0, 0, 0);
	}

	private static byte[] withHead(int size, int... head) {
		byte[] bytes = new byte[size];
		Arrays.fill(bytes, (byte) 0x2A);
		for (int i = 0; i < head.length && i < size; i++) {
			bytes[i] = (byte) head[i];
		}
		return bytes;
	}
}
