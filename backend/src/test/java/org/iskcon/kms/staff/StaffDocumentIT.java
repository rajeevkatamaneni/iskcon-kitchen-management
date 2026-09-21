package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantAwareDataSource;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A staff member's photograph and the scans of their PAN and Aadhaar cards (T-428), through the
 * whole stack and against a real PostgreSQL as the unprivileged application role.
 *
 * <p>Four things are worth proving here, and none of them can be proved with a mock.
 *
 * <p><b>That the file is what its bytes say.</b> A text file named {@code pan.jpg} and declared as
 * {@code image/jpeg} is refused, because the sender writes both the name and the declared type and
 * neither is evidence. The same check a vendor's bill gets, and the same refusal, KMS-400165.
 *
 * <p><b>That reading one is recorded.</b> Opening a scan writes {@code STAFF_DOCUMENT_VIEWED} naming
 * who, whose and which kind — and nothing else. The assertion is made against the whole audit row as
 * text, so a later change that helpfully copies the storage key or the file's name into it fails
 * here rather than quietly handing an Aadhaar card's whereabouts to everybody with VIEW_AUDIT_LOG.
 *
 * <p><b>That another temple cannot reach it.</b> Both through the API and, separately, straight at
 * the database as {@code kms_app} with the other temple's id set, because RLS is the thing actually
 * keeping these apart and a service-level check would prove only that the service has one.
 *
 * <p><b>That MANAGE_STAFF is what gates it.</b> A Kitchen Manager holds the roster and is refused at
 * all three doors, endpoint by endpoint rather than trusted to a shared annotation.
 */
@AutoConfigureMockMvc
class StaffDocumentIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String DOCUMENTS = "/api/v1/staff/members/{id}/documents";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID kitchen;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = temple("radha-govinda-docs", "Bengaluru Temple");
		otherTenant = temple("jagannath-docs", "Mysuru Temple");
		insertUser(tenant, "uid-docs-admin", "Temple Admin", "docs-admin@example.com",
				"+919876530001", "TEMPLE_ADMIN");
		insertUser(otherTenant, "uid-docs-admin-b", "Other Admin", "docs-admin-b@example.com",
				"+919876530002", "TEMPLE_ADMIN");
		kitchen = insertKitchen(tenant);
		insertKitchen(otherTenant);
		signIn("uid-docs-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		// staff_documents first: its foreign key to staff_profiles is RESTRICT.
		admin.execute("DELETE FROM staff_documents");
		admin.execute("DELETE FROM staff_previous_employment");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Attaching and reading back --------------------------------------

	@Test
	@DisplayName("a photo is attached, comes back on the record, and is streamed back as what it is")
	void attachAndOpen() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");

		String created = mvc.perform(upload(staff, "PHOTO", jpeg("gopal.jpg")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.kind").value("PHOTO"))
				// The type is what the bytes are, never what the multipart part declared.
				.andExpect(jsonPath("$.contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.originalName").value("gopal.jpg"))
				// The storage key is ours and never leaves the server.
				.andExpect(jsonPath("$.storageKey").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		String documentId = JSON.readTree(created).get("id").asText();

		mvc.perform(authed(get("/api/v1/staff/members/{id}", staff)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.documents.length()").value(1))
				.andExpect(jsonPath("$.documents[0].kind").value("PHOTO"));

		mvc.perform(authed(get(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/jpeg"))
				// Not cached on the way: this is a photograph of a person at a temple.
				.andExpect(header().string("Cache-Control", "no-store, private"))
				.andExpect(header().string("Content-Disposition",
						org.hamcrest.Matchers.containsString("inline")));
	}

	@Test
	@DisplayName("opening a photograph is NOT recorded — the page fetches it, nobody pressed anything")
	void openingAPhotoIsNotAudited() throws Exception {
		// Ruled by Rajeev, 2026-09-21. The portrait at the top right of a record is fetched the
		// moment the page renders, because the bytes only come back from an endpoint that checks the
		// permission with the token in a header and a plain <img src> cannot send one. So every open
		// of a record with a photo wrote "X's photo was opened", which nobody did. Those rows would
		// be almost everything this action ever held, burying the read it exists for.
		String staff = hire("Radha Devi", "COOK");
		String photoId = attach(staff, "PHOTO", jpeg("her-photo.jpg"));
		String aadhaarId = attach(staff, "AADHAAR_SCAN", jpeg("aadhaar-front.jpg"));

		mvc.perform(authed(get(DOCUMENTS + "/{documentId}", staff, photoId)))
				.andExpect(status().isOk());
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'STAFF_DOCUMENT_VIEWED'", Integer.class))
				.as("a photograph read writes nothing")
				.isZero();

		// And the exemption is the photograph's alone.
		mvc.perform(authed(get(DOCUMENTS + "/{documentId}", staff, aadhaarId)))
				.andExpect(status().isOk());
		assertThat(admin.queryForList(
				"SELECT after_state::text FROM audit_events WHERE action = 'STAFF_DOCUMENT_VIEWED'",
				String.class))
				.as("the Aadhaar read still writes exactly one row, and it is the only one")
				.containsExactly("{\"kind\": \"AADHAAR_SCAN\"}");
	}

	@Test
	@DisplayName("but attaching and replacing a photograph are still recorded")
	void writingAPhotoIsStillAudited() throws Exception {
		// Rajeev, 2026-09-21: "Uploading a Photo or changing a photo should be audited." Only the
		// read is exempt; every write stays on the log, and a replacement is a removal and an
		// addition because that is what happened.
		String staff = hire("Radha Devi", "COOK");
		attach(staff, "PHOTO", jpeg("first.jpg"));
		attach(staff, "PHOTO", jpeg("second.jpg"));

		// In any order, for the reason the replacement test below already records: a replacement
		// writes both rows in ONE transaction, audit_events.created_at defaults to now(), and in
		// PostgreSQL that is the transaction's start time — so the two share a timestamp and the
		// tiebreaker is a random uuid. What matters is that all three facts are on the log.
		assertThat(actions())
				.containsExactlyInAnyOrder(
						"STAFF_DOCUMENT_ADDED", "STAFF_DOCUMENT_REMOVED", "STAFF_DOCUMENT_ADDED");
	}

	@Test
	@DisplayName("opening a scan is recorded, naming who, whose and which kind — and nothing else")
	void openingIsAudited() throws Exception {
		String staff = hire("Radha Devi", "COOK");
		String documentId = attach(staff, "AADHAAR_SCAN", jpeg("aadhaar-front.jpg"));

		mvc.perform(authed(get(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isOk());

		Map<String, Object> row = admin.queryForMap("""
				SELECT action, entity_type, entity_id, before_state, after_state, actor_label,
				       to_jsonb(a)::text AS whole
				FROM audit_events a WHERE action = 'STAFF_DOCUMENT_VIEWED'
				""");
		assertThat(row.get("entity_type")).isEqualTo("STAFF_MEMBER");
		assertThat(row.get("entity_id").toString()).isEqualTo(staff);
		assertThat(row.get("actor_label").toString()).contains("Temple Admin");
		assertThat(row.get("before_state"))
				.as("a pure access record changes nothing, so there is no before — V3 says so")
				.isNull();
		assertThat(row.get("after_state").toString()).isEqualTo("{\"kind\": \"AADHAAR_SCAN\"}");

		// The whole row as text. Nothing from inside the file, and nothing pointing at where it
		// lives: the audit log is read behind VIEW_AUDIT_LOG, which is a wider audience than
		// MANAGE_STAFF, and this is the entry for an Aadhaar card.
		String whole = row.get("whole").toString();
		assertThat(whole)
				.doesNotContain("storage_key")
				.doesNotContain("tenants/")
				.doesNotContain("aadhaar-front.jpg");
	}

	@Test
	@DisplayName("a second upload of a kind replaces the first, and both facts are on the log")
	void replacingAKindLeavesOneRow() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		attach(staff, "PAN_SCAN", jpeg("old.jpg"));
		attach(staff, "PAN_SCAN", png("new.png"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM staff_documents WHERE kind = 'PAN_SCAN'", Integer.class))
				.as("a photograph is *the* photograph; a second is a replacement, not an addition")
				.isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT content_type FROM staff_documents WHERE kind = 'PAN_SCAN'", String.class))
				.isEqualTo("image/png");

		// In any order, deliberately. audit_events.created_at defaults to now(), which in PostgreSQL
		// is the transaction's start time — so the removal and the addition written by one replacement
		// share a timestamp to the microsecond and cannot be sorted apart. What is being asserted is
		// that both facts are on the log, which is the thing that matters: somebody reading it must be
		// able to see that a document was taken off as well as that one was put on.
		assertThat(actions())
				.as("a replacement is a removal and an addition, because that is what happened")
				.containsExactlyInAnyOrder(
						"STAFF_DOCUMENT_ADDED", "STAFF_DOCUMENT_REMOVED", "STAFF_DOCUMENT_ADDED");
	}

	@Test
	@DisplayName("taking one off leaves no row, and says so on the log")
	void removing() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		String documentId = attach(staff, "PHOTO", jpeg("gopal.jpg"));

		mvc.perform(authed(delete(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject("SELECT count(*) FROM staff_documents", Integer.class)).isZero();
		assertThat(actions()).containsExactly("STAFF_DOCUMENT_ADDED", "STAFF_DOCUMENT_REMOVED");
		// A second delete of the same id finds nothing rather than pretending it worked.
		mvc.perform(authed(delete(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isNotFound());
	}

	// ---- What is refused --------------------------------------------------

	@Test
	@DisplayName("a text file named pan.jpg is refused, because the bytes are the only witness")
	void theBytesDecide() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");

		MockMultipartFile liar = new MockMultipartFile(
				"file", "pan.jpg", "image/jpeg", "this is not a photograph".getBytes(StandardCharsets.UTF_8));

		mvc.perform(upload(staff, "PAN_SCAN", liar))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400165"));

		assertThat(admin.queryForObject("SELECT count(*) FROM staff_documents", Integer.class)).isZero();
	}

	@Test
	@DisplayName("a file over ten megabytes is refused, with the code a bill's copy already uses")
	void tooLarge() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");

		byte[] huge = new byte[(int) (10L * 1024 * 1024) + 1];
		huge[0] = (byte) 0xFF;
		huge[1] = (byte) 0xD8;
		huge[2] = (byte) 0xFF;

		mvc.perform(upload(staff, "PHOTO", new MockMultipartFile("file", "big.jpg", "image/jpeg", huge)))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("KMS-400166"));
	}

	@Test
	@DisplayName("a kind nobody has heard of is answered in our own words, not Spring's")
	void unknownKind() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");

		mvc.perform(upload(staff, "PASSPORT", jpeg("passport.jpg")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("kind"));
	}

	@Test
	@DisplayName("choosing no file at all is answered beside the box, not as something we broke")
	void noFile() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");

		mvc.perform(authed(multipart(DOCUMENTS, staff)).param("kind", "PHOTO"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
	}

	// ---- Who may, and whose ----------------------------------------------

	@Test
	@DisplayName("a kitchen manager is refused at all three doors")
	void managerIsRefused() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		String documentId = attach(staff, "PHOTO", jpeg("gopal.jpg"));

		insertUser(tenant, "uid-docs-manager", "Kitchen Manager", "docs-manager@example.com",
				"+919876530003", "KITCHEN_MANAGER");
		signIn("uid-docs-manager");

		mvc.perform(upload(staff, "PHOTO", jpeg("another.jpg"))).andExpect(status().isForbidden());
		mvc.perform(authed(get(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isForbidden());
		mvc.perform(authed(delete(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("another temple's administrator finds nothing, at the API and at the database")
	void anotherTempleCannotReachIt() throws Exception {
		String staff = hire("Gopal Das", "HEAD_COOK");
		String documentId = attach(staff, "AADHAAR_SCAN", jpeg("aadhaar.jpg"));

		signIn("uid-docs-admin-b");
		// Not 403: their temple simply has no such person, and saying "forbidden" would confirm the
		// id belongs to somebody somewhere.
		mvc.perform(authed(get(DOCUMENTS + "/{documentId}", staff, documentId)))
				.andExpect(status().isNotFound());
		mvc.perform(upload(staff, "PHOTO", jpeg("x.jpg"))).andExpect(status().isNotFound());

		// And straight at the database as the unprivileged application role, with the other temple
		// set: RLS is what is actually keeping these apart.
		asApplication(otherTenant, jdbc ->
				assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_documents", Integer.class))
						.as("row-level security, not the service, is what hides another temple's papers")
						.isZero());
		asApplication(tenant, jdbc ->
				assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_documents", Integer.class))
						.isEqualTo(1));
	}

	// ---------------------------------------------------------------------

	private List<String> actions() {
		return admin.queryForList(
				"SELECT action FROM audit_events WHERE action LIKE 'STAFF_DOCUMENT%' ORDER BY created_at, id",
				String.class);
	}

	/** The smallest thing PostgreSQL and AttachmentFileType will both call a JPEG. */
	private static MockMultipartFile jpeg(String name) {
		byte[] bytes = new byte[64];
		Arrays.fill(bytes, (byte) 0x20);
		bytes[0] = (byte) 0xFF;
		bytes[1] = (byte) 0xD8;
		bytes[2] = (byte) 0xFF;
		return new MockMultipartFile("file", name, "image/jpeg", bytes);
	}

	private static MockMultipartFile png(String name) {
		byte[] bytes = new byte[64];
		byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		// Declared as a JPEG on purpose: what is stored must be what the bytes are.
		return new MockMultipartFile("file", name, "image/jpeg", bytes);
	}

	private MockHttpServletRequestBuilder upload(String staffId, String kind, MockMultipartFile file) {
		return authed(multipart(DOCUMENTS, staffId).file(file)).param("kind", kind);
	}

	private String attach(String staffId, String kind, MockMultipartFile file) throws Exception {
		String body = mvc.perform(upload(staffId, kind, file))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private String hire(String name, String jobTitle) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"fullName":"%s","jobTitle":"%s","employmentType":"FULL_TIME",
								 "dateOfJoining":"2026-02-01","kitchenId":"%s"}
								""".formatted(name, jobTitle, kitchen)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	/** Runs a statement as the unprivileged application role, scoped to one temple. */
	private void asApplication(UUID temple, java.util.function.Consumer<JdbcTemplate> work) {
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		TenantContext.set(temple);
		try {
			work.accept(new JdbcTemplate(new TenantAwareDataSource(plain)));
		} finally {
			TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID temple, String uid, String name, String email, String phone, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", temple, uid, name, email, phone, role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertKitchen(UUID temple) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				SELECT ?, 'Main kitchen', true, true, 'ACTIVE', id FROM users WHERE tenant_id = ?
				ORDER BY created_at, id LIMIT 1
				RETURNING id
				""", UUID.class, temple, temple);
	}
}
