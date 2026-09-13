package org.iskcon.kms.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.notification.NotificationTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/ops/whatsapp-templates}, the operator's catalogue of templates (T-177).
 *
 * <p>Built on exactly OpsIT's configuration, the same {@code @AutoConfigureMockMvc} and the same
 * imported stub verifier, so the two share one cached Spring context rather than adding another.
 *
 * <p>The recorded wordings are seeded as the container superuser with fixed times, so every date rule
 * is asserted against a known answer rather than against whatever this JVM's start-up happened to
 * write.
 */
@AutoConfigureMockMvc
@Import(OpsIT.StubVerifierConfiguration.class)
class WhatsAppTemplateCatalogueIT extends AbstractIntegrationTest {

	private static final Instant EARLIEST = Instant.parse("2026-09-13T03:00:00Z");
	private static final Instant RELEASE = Instant.parse("2026-09-13T04:00:00Z");
	private static final Instant REWORDED = Instant.parse("2026-09-14T05:30:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private OpsIT.StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;
	private UUID temple;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		admin.update("DELETE FROM whatsapp_template_wording_seen");
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("an operator gets every template, its body and examples as the enum has them, and the dates as defined")
	void operatorSeesTheCatalogue() throws Exception {
		seedWordings();
		signInAsSuperAdmin();

		String body = mvc.perform(authed(get("/api/v1/ops/whatsapp-templates")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		JsonNode catalogue = json.readTree(body);

		assertThat(Instant.parse(catalogue.get("trackingSince").asText())).isEqualTo(EARLIEST);

		JsonNode templates = catalogue.get("templates");
		NotificationTemplate[] all = NotificationTemplate.values();
		assertThat(templates.size()).as("every template, once").isEqualTo(all.length);
		for (int i = 0; i < all.length; i++) {
			NotificationTemplate t = all[i];
			JsonNode entry = templates.get(i);
			assertThat(fieldNames(entry)).containsExactlyInAnyOrder("name", "category", "language", "body",
					"exampleValues", "usedBy", "wordingFirstSeenAt", "wordingLastChangedAt");
			assertThat(entry.get("name").asText()).isEqualTo(t.whatsappTemplateName());
			assertThat(entry.get("category").asText()).isEqualTo(t.whatsappCategory());
			assertThat(entry.get("language").asText()).isEqualTo("en");
			assertThat(entry.get("body").asText()).isEqualTo(t.whatsappBodyText());
			assertThat(strings(entry.get("exampleValues"))).isEqualTo(t.whatsappExampleValues());
			assertThat(strings(entry.get("usedBy"))).isEqualTo(t.usedBy()).isNotEmpty();
		}

		// Two wordings recorded, the current one later: first seen is the older, changed is the newer.
		assertDates(templates, "volunteer_shift_reminder", RELEASE, REWORDED);
		// Two wordings, the older one is the earliest row in the table.
		assertDates(templates, "po_delivery", EARLIEST, RELEASE);
		// One wording, the current one: not changed since tracking began.
		assertDates(templates, "shift_broadcast", RELEASE, null);
		// Nothing recorded for this name at all.
		assertDates(templates, "donation_receipt", null, null);
		// Only an earlier wording recorded, never the one running now: said as not recorded, both dates.
		assertDates(templates, "leave_revoked", null, null);
	}

	@Test
	@DisplayName("an empty table gives a null tracking date and null dates, never an error")
	void emptyTable() throws Exception {
		signInAsSuperAdmin();

		JsonNode catalogue = json.readTree(mvc.perform(authed(get("/api/v1/ops/whatsapp-templates")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

		assertThat(catalogue.has("trackingSince")).isTrue();
		assertThat(catalogue.get("trackingSince").isNull()).isTrue();
		assertThat(catalogue.get("templates").size()).isEqualTo(NotificationTemplate.values().length);
	}

	@Test
	@DisplayName("a temple admin is refused the catalogue")
	void templeAdminIsForbidden() throws Exception {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@govinda.example', '+919876500050',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);
		stubVerifier.accept("uid-admin");

		mvc.perform(authed(get("/api/v1/ops/whatsapp-templates"))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("it reads no temple table: its only field is a JdbcTemplate, and every statement it runs plans against the wording table alone")
	void readsNoTempleTable() throws Exception {
		List<Class<?>> fields = Arrays.stream(WhatsAppTemplateCatalogue.class.getDeclaredFields())
				.filter(f -> !Modifier.isStatic(f.getModifiers()))
				.<Class<?>>map(java.lang.reflect.Field::getType)
				.toList();
		assertThat(fields).as("nothing to reach a temple table through but its own JdbcTemplate")
				.containsExactly(JdbcTemplate.class);

		seedWordings();
		List<String> issued = new ArrayList<>();
		try (Connection real = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD)) {
			new WhatsAppTemplateCatalogue(new JdbcTemplate(
					new SingleConnectionDataSource(recording(real, issued), true))).read();

			assertThat(issued).as("the read issued at least one statement, so the check below is live").isNotEmpty();

			Set<String> relations = new TreeSet<>();
			JdbcTemplate app = new JdbcTemplate(new SingleConnectionDataSource(real, true));
			for (String sql : issued) {
				String plan = app.queryForObject("EXPLAIN (FORMAT JSON) " + sql, String.class);
				json.readTree(plan).findValuesAsText("Relation Name").forEach(relations::add);
			}
			assertThat(relations).containsExactly("whatsapp_template_wording_seen");
		}
	}

	// ---------------------------------------------------------------------

	private void seedWordings() {
		for (NotificationTemplate t : NotificationTemplate.values()) {
			String name = t.whatsappTemplateName();
			String current = t.whatsappFingerprint("en");
			switch (name) {
				case "volunteer_shift_reminder" -> {
					seed(name, "sha256:older-volunteer-shift-reminder", RELEASE);
					seed(name, current, REWORDED);
				}
				case "po_delivery" -> {
					seed(name, "sha256:older-po-delivery", EARLIEST);
					seed(name, current, RELEASE);
				}
				case "donation_receipt" -> {
					// nothing recorded
				}
				case "leave_revoked" -> seed(name, "sha256:older-leave-revoked", RELEASE);
				default -> seed(name, current, RELEASE);
			}
		}
	}

	private void seed(String name, String fingerprint, Instant at) {
		admin.update("INSERT INTO whatsapp_template_wording_seen (template_name, fingerprint, first_seen_at) VALUES (?, ?, ?)",
				name, fingerprint, java.sql.Timestamp.from(at));
	}

	private static void assertDates(JsonNode templates, String name, Instant firstSeen, Instant lastChanged) {
		JsonNode entry = null;
		for (JsonNode t : templates) {
			if (t.get("name").asText().equals(name)) {
				entry = t;
			}
		}
		assertThat(entry).as(name).isNotNull();
		assertThat(instantOrNull(entry.get("wordingFirstSeenAt"))).as("%s first seen", name).isEqualTo(firstSeen);
		assertThat(instantOrNull(entry.get("wordingLastChangedAt"))).as("%s last changed", name).isEqualTo(lastChanged);
	}

	private static Instant instantOrNull(JsonNode node) {
		return node == null || node.isNull() ? null : Instant.parse(node.asText());
	}

	private static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		array.forEach(n -> out.add(n.asText()));
		return out;
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	/** A connection that writes down the SQL of every statement prepared or executed on it. */
	private static Connection recording(Connection real, List<String> issued) {
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
				(p, method, args) -> {
					String name = method.getName();
					if ((name.equals("prepareStatement") || name.equals("prepareCall"))
							&& args != null && args[0] instanceof String sql) {
						issued.add(sql);
					}
					Object result = call(real, method, args);
					if (name.equals("createStatement")) {
						Statement statement = (Statement) result;
						return Proxy.newProxyInstance(
								Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
								(p2, m2, a2) -> {
									if (m2.getName().startsWith("execute") && a2 != null && a2[0] instanceof String sql) {
										issued.add(sql);
									}
									return call(statement, m2, a2);
								});
					}
					return result;
				});
	}

	private static Object call(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com', '+919000000001',
						'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}
}
