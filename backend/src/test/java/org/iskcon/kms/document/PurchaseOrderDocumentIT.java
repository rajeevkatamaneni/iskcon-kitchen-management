package org.iskcon.kms.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 * Purchase-order documents (E5-S4): versioned generation, download, and the browser print view. A
 * mocked {@link Scheduler} makes the request→enqueue path hermetic (no real Quartz context), and the
 * worker step is driven synchronously through {@link DocumentGenerationService}, like the recipe
 * generation test.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderDocumentIT.StubVerifierConfiguration.class)
class PurchaseOrderDocumentIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	// A no-op scheduler: enqueue becomes a no-op, so requestPurchaseOrderPdf still writes the
	// versioned PENDING row without standing up a real Quartz context.
	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID dal;
	private UUID vendor;

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
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		rice = ingredient("Rice");
		dal = ingredient("Toor Dal");
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, address, gstin, phone)
				VALUES (?, 'Govind Wholesale', '12 Market Rd, Bengaluru', '29ABCDE1234F1Z5', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
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
	@DisplayName("each request is a retained new version, latest first, and the sheet downloads as a PDF")
	void versionsRetainedAndDownloadable() throws Exception {
		UUID poId = pricedPo("PO-2026-0042");

		String d1 = requestVersion(poId);
		String d2 = requestVersion(poId);

		// Two versions, latest first.
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/documents", poId)))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].version").value(2))
				.andExpect(jsonPath("$[1].version").value(1));

		// Drive the worker for the latest version, then download it.
		generateWithin(UUID.fromString(d2));
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/documents/{id}/download", poId, d2)))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"));

		// The earlier version is still retrievable (retained), independent of the latest.
		generateWithin(UUID.fromString(d1));
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/documents/{id}", poId, d1)))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	@DisplayName("the print view renders the sheet as HTML with the vendor and the price column")
	void printViewWithPrices() throws Exception {
		UUID poId = pricedPo("PO-2026-0043");
		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/print", poId)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("PO-2026-0043")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Govind Wholesale")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("GSTIN: 29ABCDE1234F1Z5")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(">Price<")));
	}

	@Test
	@DisplayName("a PO with no line prices prints without a price column")
	void printViewWithoutPrices() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0044', ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, 10, 'KG')
				""", tenant, poId, rice);

		mvc.perform(authed(get("/api/v1/purchase-orders/{poId}/print", poId)))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(">Price<"))));
	}

	@Test
	@DisplayName("a volunteer cannot request PO documents")
	void volunteerForbidden() throws Exception {
		UUID poId = pricedPo("PO-2026-0045");
		signIn("uid-vol-a");
		mvc.perform(authed(post("/api/v1/purchase-orders/{poId}/pdf", poId)))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private String requestVersion(UUID poId) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/purchase-orders/{poId}/pdf", poId)))
				.andExpect(status().isAccepted())
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

	private UUID pricedPo(String number) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, needed_by,
					delivery_location, notes, created_by)
				VALUES (?, ?, ?, 'SENT', DATE '2026-08-20', 'Main kitchen store', 'Deliver before noon', ?)
				RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price, line_order)
				VALUES (?, ?, ?, 30, 'KG', 45.00, 0)
				""", tenant, poId, rice);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price, line_order)
				VALUES (?, ?, ?, 10, 'KG', 120.00, 1)
				""", tenant, poId, dal);
		return poId;
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
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
