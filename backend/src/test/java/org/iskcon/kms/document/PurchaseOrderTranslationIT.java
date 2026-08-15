package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PO translation (E5-S5): the vendor's default language is pre-selected and overridable, static
 * labels are curated per language, ingredient names go glossary-first then MT, and the engine is
 * recorded as provenance. The stub translation provider tags each string with {@code [lang]}, so a
 * translated string is recognisable and a glossary override is distinguishable from an MT result.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderTranslationIT.StubVerifierConfiguration.class)
class PurchaseOrderTranslationIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID hindiVendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		hindiVendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone, preferred_language)
				VALUES (?, 'Govind Wholesale', '+919812345678', 'hi') RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_label_translations");
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the vendor's default language is pre-selected, and an explicit language overrides it")
	void vendorDefaultAndOverride() throws Exception {
		UUID poId = po(hindiVendor, "PO-2026-0042");

		// No language → the Hindi vendor's default.
		String def = requestPdf(poId, null);
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/documents/{id}", poId, def)))
				.andExpect(jsonPath("$.language").value("hi"));

		// Explicit override.
		String override = requestPdf(poId, "te");
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/documents/{id}", poId, override)))
				.andExpect(jsonPath("$.language").value("te"));
	}

	@Test
	@DisplayName("the Hindi print view uses curated labels, translates the item, and leaves the PO number alone")
	void hindiPrintView() throws Exception {
		UUID poId = po(hindiVendor, "PO-2026-0043");
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/print", poId).param("language", "hi")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("[hi] Purchase Order"))) // MT label
				.andExpect(content().string(Matchers.containsString("[hi] Rice")))            // MT ingredient
				.andExpect(content().string(Matchers.containsString("PO-2026-0043")));        // number untouched
	}

	@Test
	@DisplayName("labels cached by another provider are re-translated, not printed")
	void otherProvidersLabelCacheIsIgnored() throws Exception {
		// What a previous engine left behind. Served blindly, a vendor's sheet reads "[STALE] TO".
		admin.update("""
				INSERT INTO po_label_translations (tenant_id, language, label_set_version, content, provider)
				VALUES (?, 'hi', 1, CAST(? AS jsonb), 'google')
				""", tenant, "[\"[STALE] Purchase Order\",\"[STALE] To\",\"[STALE] Order date\"]");
		UUID poId = po(hindiVendor, "PO-2026-0046");

		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/print", poId).param("language", "hi")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("[hi] Purchase Order")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("[STALE]"))));

		assertThat(admin.queryForObject(
				"SELECT provider FROM po_label_translations WHERE language = 'hi'", String.class))
				.as("the stale row is replaced, not duplicated")
				.isEqualTo("stub");
	}

	@Test
	@DisplayName("a glossary term wins over machine translation for an ingredient")
	void glossaryOverridesMt() throws Exception {
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (?, 'hi', 'Rice', 'चावल')
				""", tenant);
		UUID poId = po(hindiVendor, "PO-2026-0044");

		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/print", poId).param("language", "hi")))
				.andExpect(content().string(Matchers.containsString("चावल")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("[hi] Rice"))));
	}

	@Test
	@DisplayName("a translated sheet records the MT engine as provenance; an English sheet records none")
	void provenanceRecorded() throws Exception {
		UUID poId = po(hindiVendor, "PO-2026-0045");

		String hi = requestPdf(poId, "hi");
		generateWithin(UUID.fromString(hi));
		String en = requestPdf(poId, "en");
		generateWithin(UUID.fromString(en));

		String hiProv = admin.queryForObject(
				"SELECT translation_provider FROM documents WHERE id = ?", String.class, UUID.fromString(hi));
		String enProv = admin.queryForObject(
				"SELECT translation_provider FROM documents WHERE id = ?", String.class, UUID.fromString(en));
		assert "stub".equals(hiProv) : "expected stub provenance, got " + hiProv;
		assert enProv == null : "English sheet should have no translation provenance";
	}

	// ---------------------------------------------------------------------

	private String requestPdf(UUID poId, String language) throws Exception {
		MockHttpServletRequestBuilder req = authed(post("/api/v1/purchase-orders/{poId}/pdf", poId));
		if (language != null) {
			req = req.param("language", language);
		}
		String body = mvc.perform(req).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("documentId").asText();
	}

	private void generateWithin(UUID docId) {
		TenantContext.set(tenant);
		try {
			generationService.generate(docId);
		} finally {
			TenantContext.clear();
		}
	}

	private UUID po(UUID vendor, String number) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, ?, ?, 'SENT', ?) RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, 30, 'KG')
				""", tenant, poId, rice);
		return poId;
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
