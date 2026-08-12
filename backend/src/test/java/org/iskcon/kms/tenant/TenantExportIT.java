package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The copy taken before a temple is erased (E1-S15).
 *
 * <p>Run against a real database on purpose: what the export must contain is decided by the same
 * catalogue query the purge uses, and what it must <em>not</em> contain — another temple's rows — is
 * decided by row-level security. Both are database behaviours, and the tests run as the unprivileged
 * application role, so an isolation failure would show up here rather than being mocked away.
 */
@AutoConfigureMockMvc
@Import(TenantExportIT.StubVerifierConfiguration.class)
class TenantExportIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the export carries the temple's own row and a sheet for every tenant-owned table")
	void exportCoversEverythingThePurgeWouldDestroy() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		signInAsSuperAdmin();

		byte[] file = export(temple);

		try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			// The temple's own row leads the workbook.
			assertThat(book.getSheetIndex("tenants")).isZero();
			Sheet tenants = book.getSheet("tenants");
			assertThat(tenants.getLastRowNum()).isEqualTo(1);

			// A sheet exists for every table the purge would empty, whether or not it held anything.
			for (String table : tenantOwnedTables()) {
				assertThat(book.getSheet(sheetNameOf(book, table)))
						.as("a sheet for %s", table)
						.isNotNull();
			}

			// The seeded rows are there, under their real column names.
			Sheet ingredients = book.getSheet("ingredients");
			assertThat(headerOf(ingredients)).contains("name", "canonical_unit", "tenant_id");
			assertThat(ingredients.getLastRowNum()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("the export contains this temple's rows and no other temple's")
	void exportIsConfinedToOneTemple() throws Exception {
		UUID mine = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID theirs = seedTemple("krishna-balaram", "Sri Krishna Balaram Temple");
		admin.update(
				"INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, 'Ghee', 'Dairy', 'L')",
				theirs);
		signInAsSuperAdmin();

		byte[] file = export(mine);

		try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			Sheet ingredients = book.getSheet("ingredients");
			int tenantColumn = headerOf(ingredients).indexOf("tenant_id");

			for (int row = 1; row <= ingredients.getLastRowNum(); row++) {
				assertThat(ingredients.getRow(row).getCell(tenantColumn).getStringCellValue())
						.as("every exported row belongs to the exported temple")
						.isEqualTo(mine.toString());
			}
			// The other temple's Ghee is nowhere in the file.
			assertThat(ingredients.getLastRowNum()).isEqualTo(1);

			Sheet tenants = book.getSheet("tenants");
			assertThat(tenants.getLastRowNum()).as("only the exported temple's own row").isEqualTo(1);
			assertThat(rowContains(tenants, theirs.toString())).isFalse();
		}
	}

	@Test
	@DisplayName("taking an export is recorded on the platform log, with what it contained")
	void exportIsAudited() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		signInAsSuperAdmin();

		export(temple);

		Map<String, Object> event = admin.queryForMap("""
				SELECT action, entity_id, after_state::text AS after_state, reason
				FROM platform_audit_events
				WHERE action = 'TENANT_EXPORTED' AND entity_id = ?
				""", temple);

		assertThat(event.get("entity_id")).isEqualTo(temple);
		// The row counts are the record of what was copied — the answer to "what did we have?" once
		// the temple itself is gone.
		assertThat(String.valueOf(event.get("after_state")))
				.contains("\"ingredients\": 1")
				.contains("\"users\": 1")
				.contains("\"tenants\": 1");
	}

	@Test
	@DisplayName("the file is named after the temple, so it still says whose data it is a year later")
	void fileIsNamedAfterTheTemple() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		signInAsSuperAdmin();

		String disposition = mvc.perform(authed(get("/api/v1/tenants/{id}/export", temple)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getHeader("Content-Disposition");

		assertThat(disposition).contains("attachment").contains("radha-govinda-ikms-data-export.xlsx");
	}

	@Test
	@DisplayName("a temple admin cannot export a temple")
	void templeAdminIsForbidden() throws Exception {
		UUID temple = seedTemple("radha-govinda", "Sri Sri Radha Govinda Temple");
		stubVerifier.accept("uid-radha-govinda");

		mvc.perform(authed(get("/api/v1/tenants/{id}/export", temple)))
				.andExpect(status().isForbidden());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM platform_audit_events WHERE action = 'TENANT_EXPORTED'",
				Integer.class)).isZero();
	}

	@Test
	@DisplayName("exporting an unknown temple is a 404, not an empty workbook")
	void unknownTempleIsNotFound() throws Exception {
		signInAsSuperAdmin();

		mvc.perform(authed(get("/api/v1/tenants/{id}/export", UUID.randomUUID())))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------------

	private byte[] export(UUID tenantId) throws Exception {
		return mvc.perform(authed(get("/api/v1/tenants/{id}/export", tenantId)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsByteArray();
	}

	private java.util.List<String> tenantOwnedTables() {
		return admin.queryForList("""
				SELECT c.relname
				FROM pg_class c
				JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
				WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
				""", String.class);
	}

	/** Excel truncates long names, so match the sheet the way the workbook would have named it. */
	private String sheetNameOf(Workbook book, String table) {
		String truncated = table.length() > 31 ? table.substring(0, 31) : table;
		return book.getSheet(truncated) != null ? truncated : table;
	}

	private java.util.List<String> headerOf(Sheet sheet) {
		java.util.List<String> header = new java.util.ArrayList<>();
		sheet.getRow(0).forEach(cell -> header.add(cell.getStringCellValue()));
		return header;
	}

	private boolean rowContains(Sheet sheet, String value) {
		for (int row = 1; row <= sheet.getLastRowNum(); row++) {
			for (int col = 0; col < sheet.getRow(row).getLastCellNum(); col++) {
				var cell = sheet.getRow(row).getCell(col);
				if (cell != null
						&& cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
						&& value.equals(cell.getStringCellValue())) {
					return true;
				}
			}
		}
		return false;
	}

	private UUID seedTemple(String slug, String name) {
		UUID temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);

		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Temple Admin', ?, '+919876500050', 'TEMPLE_ADMIN', 'ACTIVE')
				""", temple, "uid-" + slug, slug + "@example.com");

		admin.update(
				"INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, 'Rice', 'Grains', 'KG')",
				temple);

		return temple;
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com', '+919000000001',
						'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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
