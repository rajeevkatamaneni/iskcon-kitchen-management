package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V141 (the App ID column) and V142 (stored reasons that said "Press Reload"), run against a database whose
 * temples already hold stored reasons, as staging's do (T-200).
 *
 * <p>The suite's own database is migrated empty, so a per-temple loop like V142's would run its body zero
 * times there and prove nothing. {@code TenantLoopMigrationIT} plans the loop against a temple with no
 * WhatsApp row; this class goes one step further and gives two temples rows with the old sentences in
 * them, so the rewrite itself is checked, not only that it parses. It builds a throwaway database the same
 * way, migrates it as the unprivileged migration role to V141, seeds through the superuser, and runs V142
 * on top: the role has no BYPASSRLS, so a V142 that forgot to adopt each temple would change nothing and
 * fail here.
 */
class WhatsAppAppIdMigrationIT extends AbstractIntegrationTest {

	private static final String DATABASE = "kms_t200_reason_rewrite";

	private static final String BUTTON = TenantWhatsAppSettingsService.TEMPLATES_BUTTON;

	/** The six sentences 5cb4550 (T-188) replaced, exactly as that commit's parent wrote them. */
	private static final Map<String, String> OLD_TO_CURRENT = Map.of(
			"Meta did not say which wording it holds for this message. Press Reload to try again.",
			TenantWhatsAppSettingsService.NOT_TOLD_WHAT_META_HOLDS,
			"Meta is still reviewing this message, so its new wording has to wait. Press Reload again once the review is over.",
			TenantWhatsAppSettingsService.STILL_IN_REVIEW,
			"Meta allows a message to be reworded only once a day and ten times a month. Press Reload again tomorrow.",
			TenantWhatsAppSettingsService.EDIT_LIMIT,
			"Meta is not taking new wording for this message yet, because of a review or a recent change. Press Reload again tomorrow.",
			TenantWhatsAppSettingsService.IN_REVIEW_OR_EDIT_LIMIT,
			"Meta could not be reached while this message was being registered. Press Reload to try again.",
			"Meta could not be reached while this message was being registered. Try again with " + BUTTON + ".",
			"Meta did not accept this message. Press Reload to try again, and if it is refused again, report it with the message name shown here.",
			TenantWhatsAppSettingsService.plainReason("something Meta has never said"));

	private final ObjectMapper json = new ObjectMapper();

	@Test
	@DisplayName("V142 rewrites every stored 'Press Reload' reason to today's sentence, per temple, and touches nothing else")
	void storedReasonsAreRewritten() throws Exception {
		recreateDatabase();
		migrate("141");

		List<Map<String, String>> first = new ArrayList<>();
		OLD_TO_CURRENT.keySet().stream().sorted().forEach(old -> first.add(Map.of(
				"name", "template_" + first.size(), "reason", old, "kind", "REFUSED")));
		// A sentence no version of the code wrote as an old one, and T-159's older one, are both left alone.
		String untouched = "Meta holds this message as marketing, which some countries do not deliver.";
		String t159 = "Meta did not accept this message. Press Save to try again, and if it is refused again, "
				+ "report it with the message name shown here.";
		first.add(Map.of("name", "donation_thank_you", "reason", untouched, "kind", "HELD_UNDER_ANOTHER_CATEGORY"));
		first.add(Map.of("name", "leave_approved", "reason", t159));
		List<Map<String, String>> second = List.of(Map.of("name", "po_delivery",
				"reason", "Meta is still reviewing this message, so its new wording has to wait. Press Reload again once the review is over.",
				"kind", "REFUSED"));
		List<Map<String, String>> third = List.of(Map.of("name", "shift_cancelled", "reason", untouched));

		String templeA = seedTemple("t200-a", json.writeValueAsString(first));
		String templeB = seedTemple("t200-b", json.writeValueAsString(second));
		String templeC = seedTemple("t200-c", json.writeValueAsString(third));
		String untouchedBefore = storedList(templeC);

		migrate(null);

		JsonNode a = json.readTree(storedList(templeA));
		assertThat(a).hasSize(first.size());
		for (int i = 0; i < first.size(); i++) {
			Map<String, String> before = first.get(i);
			JsonNode after = a.get(i);
			assertThat(after.get("name").asText()).as("order and names kept").isEqualTo(before.get("name"));
			String expected = OLD_TO_CURRENT.getOrDefault(before.get("reason"), before.get("reason"));
			assertThat(after.get("reason").asText()).as(before.get("name")).isEqualTo(expected);
			assertThat(after.path("kind").isMissingNode() ? null : after.get("kind").asText())
					.as("kind kept, and not added where there was none").isEqualTo(before.get("kind"));
		}
		assertThat(a.toString()).doesNotContain("Press Reload");
		assertThat(a.get(first.size() - 1).get("reason").asText()).as("T-159's sentence is not this migration's").isEqualTo(t159);

		assertThat(json.readTree(storedList(templeB)).get(0).get("reason").asText())
				.isEqualTo(TenantWhatsAppSettingsService.STILL_IN_REVIEW);
		assertThat(storedList(templeC)).as("a list with no old sentence is not rewritten").isEqualTo(untouchedBefore);

		// The current sentences really are what the code writes, so this rewrite and the code cannot drift.
		assertThat(OLD_TO_CURRENT.values()).allSatisfy(current -> assertThat(current).contains(BUTTON).doesNotContain("Press Reload"));
	}

	@Test
	@DisplayName("V141 stores an App ID of digits, allows none, and refuses anything else")
	void appIdIsDigitsOnly() throws Exception {
		recreateDatabase();
		migrate(null);
		String temple = seedTemple("t200-app-id", "[]");

		setAppId(temple, "1234567890123456");
		setAppId(temple, null);
		assertThatThrownBy(() -> setAppId(temple, "EAAG-not-an-app-id"))
				.isInstanceOf(SQLException.class).hasMessageContaining("tenant_settings_whatsapp_app_id_is_digits");
		assertThatThrownBy(() -> setAppId(temple, ""))
				.isInstanceOf(SQLException.class).hasMessageContaining("tenant_settings_whatsapp_app_id_is_digits");
	}

	// ---------------------------------------------------------------------

	private void migrate(String target) {
		var configure = Flyway.configure()
				.dataSource(urlFor(DATABASE), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration");
		if (target != null) {
			configure.target(target);
		}
		configure.load().migrate();
	}

	/** A temple with a tenant_settings row holding this refused list. Through the superuser: the seed is not under test. */
	private String seedTemple(String slug, String refusedList) throws SQLException {
		try (Connection connection = superuser();
				PreparedStatement temple = connection.prepareStatement("""
						INSERT INTO tenants (slug, name, latitude, longitude, timezone)
						VALUES (?, 'T-200 migration temple', 12.97, 77.59, 'Asia/Kolkata') RETURNING id
						""")) {
			temple.setString(1, slug);
			String id;
			try (ResultSet rs = temple.executeQuery()) {
				rs.next();
				id = rs.getString(1);
			}
			try (PreparedStatement settings = connection.prepareStatement("""
					INSERT INTO tenant_settings (tenant_id, whatsapp_refused_templates) VALUES (?::uuid, ?::jsonb)
					""")) {
				settings.setString(1, id);
				settings.setString(2, refusedList);
				settings.executeUpdate();
			}
			return id;
		}
	}

	private String storedList(String temple) throws SQLException {
		try (Connection connection = superuser();
				PreparedStatement read = connection.prepareStatement(
						"SELECT whatsapp_refused_templates::text FROM tenant_settings WHERE tenant_id = ?::uuid")) {
			read.setString(1, temple);
			try (ResultSet rs = read.executeQuery()) {
				rs.next();
				return rs.getString(1);
			}
		}
	}

	private void setAppId(String temple, String appId) throws SQLException {
		try (Connection connection = superuser();
				PreparedStatement update = connection.prepareStatement(
						"UPDATE tenant_settings SET whatsapp_app_id = ? WHERE tenant_id = ?::uuid")) {
			update.setString(1, appId);
			update.setString(2, temple);
			update.executeUpdate();
		}
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// As TenantLoopMigrationIT: the migration role owns the schema, so a migration that only works when
		// RLS is bypassed still fails.
		try (Connection connection = superuser();
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private static Connection superuser() throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(DATABASE));
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource.getConnection();
	}

	private static String urlFor(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database);
	}
}
