package org.iskcon.kms.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Theme packs, end to end (V72, V73).
 *
 * <p>The whole feature rests on two claims that are easy to state and easy to get wrong, so both
 * are asserted here rather than reasoned about.
 *
 * <p><b>The choice belongs to the temple, not to the person.</b> An administrator picks, and the
 * cook who never opens Settings sees it. A kitchen where two people see two different colours is a
 * kitchen where they cannot describe a screen to each other over the noise of a Sunday feast.
 *
 * <p><b>The catalogue belongs to the platform.</b> It is one table shared by every temple, so the
 * only thing standing between a temple administrator and everybody else's colours is the RLS
 * policy — there is no tenant column to scope it. That policy is tested here as the policy,
 * running as the application role with no Java in the way, because a control that is only enforced
 * by an annotation is a convention.
 */
@AutoConfigureMockMvc
@Import(ThemeIT.StubVerifierConfiguration.class)
class ThemeIT extends AbstractIntegrationTest {

	/** The pack V72 seeds and every temple starts on. */
	private static final String DEFAULT_SLUG = "temple-terracotta";

	/** The pack V73 seeds, and the accent that identifies it on sight. */
	private static final String BLUE_SLUG = "harbour-blue";
	private static final String BLUE_ACCENT = "#2573B3";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-cook-a", "KITCHEN_STAFF");
		insertUser(templeB, "uid-admin-b", "TEMPLE_ADMIN");
		insertOperator("uid-operator");
	}

	@AfterEach
	void tearDown() {
		// tenant_settings first: it holds the reference to theme_packs and the reference to
		// tenants, and both are RESTRICT.
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
		// theme_packs is deliberately not cleared. It is seeded by migration, shared by every
		// temple, and emptying it between tests would be emptying the product.
	}

	// ------------------------------------------------------------ the catalogue

	@Test
	@DisplayName("a temple admin sees the packs that shipped, each one complete")
	void catalogue() throws Exception {
		signIn("uid-admin-a");

		mvc.perform(authed(get("/api/v1/themes")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.slug=='" + DEFAULT_SLUG + "')]").exists())
				.andExpect(jsonPath("$[?(@.slug=='" + BLUE_SLUG + "')]").exists())
				// Twenty-three roles, or a screen paints most of the way and stops. The database
				// CHECK refuses an incomplete pack on the way in; this is the other end of it.
				.andExpect(jsonPath("$[?(@.slug=='" + BLUE_SLUG + "')].palette['accent']")
						.value(BLUE_ACCENT))
				.andExpect(jsonPath("$[?(@.slug=='" + BLUE_SLUG + "')].palette['focus-ring']").exists())
				.andExpect(jsonPath("$[?(@.slug=='" + BLUE_SLUG + "')].palette['success-bg']").exists());
	}

	@Test
	@DisplayName("the bright packs come before the quiet ones, whatever order they were seeded in")
	void catalogueIsOrderedByLoudness() throws Exception {
		signIn("uid-admin-a");

		// harbour-blue is BALANCED and terracotta is MUTED, so the blue leads even though it was
		// seeded second. Somebody who knows they want something quiet scrolls; somebody who does
		// not is shown the loud end first, which is the axis the choice is actually made on.
		mvc.perform(authed(get("/api/v1/themes")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].slug").value(BLUE_SLUG))
				.andExpect(jsonPath("$[1].slug").value(DEFAULT_SLUG));
	}

	@Test
	@DisplayName("a cook is not offered a choice they cannot make")
	void catalogueIsAdminOnly() throws Exception {
		signIn("uid-cook-a");

		// They still see the colours their temple wears — that arrives on their session — but the
		// list of the other fourteen is not theirs to act on.
		mvc.perform(authed(get("/api/v1/themes"))).andExpect(status().isForbidden());
	}

	// --------------------------------------------------------------- the choice

	@Test
	@DisplayName("a temple that has never chosen wears the palette the application was designed in")
	void defaultsToTerracotta() throws Exception {
		signIn("uid-cook-a");

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.theme.slug").value(DEFAULT_SLUG))
				.andExpect(jsonPath("$.theme.palette['accent']").value("#AE5838"));
	}

	@Test
	@DisplayName("what the admin picks is what the cook sees, without the cook doing anything")
	void theChoiceReachesEverybody() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themePackSlug\":\"" + BLUE_SLUG + "\"}"))
				.andExpect(status().isNoContent());

		// The point of the whole feature, and the reason the palette rides on /whoami rather than
		// on a settings endpoint the cook cannot call.
		signIn("uid-cook-a");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.theme.slug").value(BLUE_SLUG))
				.andExpect(jsonPath("$.theme.palette['accent']").value(BLUE_ACCENT));
	}

	@Test
	@DisplayName("the settings screen is told what the temple has actually said, not what it is wearing")
	void settingsReportsTheStoredChoice() throws Exception {
		signIn("uid-admin-a");

		// Null before anybody chooses. Not the same as choosing the default, and both render the
		// same — the distinction is how we know whether this feature is being used at all.
		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themePackSlug").doesNotExist());

		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themePackSlug\":\"" + BLUE_SLUG + "\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themePackSlug").value(BLUE_SLUG));
	}

	@Test
	@DisplayName("one temple's colours are not another's")
	void choiceIsPerTemple() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themePackSlug\":\"" + BLUE_SLUG + "\"}"))
				.andExpect(status().isNoContent());

		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.theme.slug").value(DEFAULT_SLUG));
	}

	@Test
	@DisplayName("changing the choice again replaces it rather than adding to it")
	void choosingTwice() throws Exception {
		signIn("uid-admin-a");
		choose(BLUE_SLUG);
		choose(DEFAULT_SLUG);

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(jsonPath("$.theme.slug").value(DEFAULT_SLUG));
		assertThat(count("tenant_settings")).isEqualTo(1);
	}

	@Test
	@DisplayName("a theme that is not in the catalogue is refused by name")
	void unknownTheme() throws Exception {
		signIn("uid-admin-a");

		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themePackSlug\":\"midnight-purple\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4972"));
	}

	@Test
	@DisplayName("a cook cannot choose the temple's colours")
	void cooksCannotChoose() throws Exception {
		signIn("uid-cook-a");

		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themePackSlug\":\"" + BLUE_SLUG + "\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a platform operator belongs to no temple and is given the default")
	void operatorWearsTheDefault() throws Exception {
		signIn("uid-operator");

		// Worth asserting rather than assuming: an operator has no app.tenant_id, so the query
		// that finds a temple's choice matches nothing. The fallback has to be a palette, not null,
		// or every operator screen paints with no colours at all.
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").doesNotExist())
				.andExpect(jsonPath("$.theme.slug").value(DEFAULT_SLUG));
	}

	// ------------------------------------------------------- the policy itself

	@Test
	@DisplayName("a temple admin can read every pack and change none of them")
	void onlyAnOperatorWritesTheCatalogue() {
		int before = count("theme_packs");

		// Reading is open to everybody, deliberately: a palette is twenty-three hex values, and
		// the public giving page needs one before an identity exists.
		asUser("uid-admin-a", jdbc ->
				assertThat(jdbc.queryForObject("SELECT count(*) FROM theme_packs", Integer.class))
						.isEqualTo(before));

		// An INSERT refused by WITH CHECK raises. SQLSTATE 42501, which Spring surfaces as a
		// generic data-access failure — the assertion is that it was refused and nothing landed,
		// not on the wording of a driver's message.
		assertThatThrownBy(() -> asUser("uid-admin-a", jdbc -> jdbc.update("""
				INSERT INTO theme_packs (slug, name, family, description, palette)
				VALUES ('mine', 'Mine', 'MUTED', 'x', ?::jsonb)
				""", completePalette())))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);

		// An UPDATE and a DELETE refused by USING filter the rows away instead of raising, so the
		// assertion is a count either side rather than a throw. That is the right shape for a
		// read-side control and the reason the two are asserted differently.
		asUser("uid-admin-a", jdbc ->
				jdbc.update("UPDATE theme_packs SET name = 'Repainted' WHERE slug = ?", BLUE_SLUG));
		asUser("uid-admin-a", jdbc -> jdbc.update("DELETE FROM theme_packs WHERE slug = ?", BLUE_SLUG));

		assertThat(count("theme_packs")).isEqualTo(before);
		assertThat(nameOf(BLUE_SLUG)).isEqualTo("Harbour blue");
	}

	@Test
	@DisplayName("an operator can withdraw a pack, but not one a temple is wearing")
	void withdrawingAPackInUse() throws Exception {
		signIn("uid-admin-a");
		choose(BLUE_SLUG);

		// ON DELETE RESTRICT rather than SET NULL. Silently returning a temple to the default
		// overnight is the kind of change nobody connects to the button that caused it.
		assertThatThrownBy(() -> asUser("uid-operator",
				jdbc -> jdbc.update("DELETE FROM theme_packs WHERE slug = ?", BLUE_SLUG)))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(nameOf(BLUE_SLUG)).isEqualTo("Harbour blue");

		// Once nobody is wearing it, the same operator can take it out.
		choose(DEFAULT_SLUG);
		asUser("uid-operator", jdbc -> jdbc.update("DELETE FROM theme_packs WHERE slug = ?", BLUE_SLUG));
		assertThat(nameOf(BLUE_SLUG)).isNull();

		// Put it back, because theme_packs is not cleared between tests.
		admin.update("""
				INSERT INTO theme_packs (slug, name, family, description, palette, sort_order)
				VALUES (?, 'Harbour blue', 'BALANCED', 'A deep harbour blue on white.', ?::jsonb, 1)
				""", BLUE_SLUG, bluePalette());
	}

	@Test
	@DisplayName("a pack missing a role is refused by the database, not discovered on a screen")
	void incompletePacksAreRefused() {
		// The failure this prevents is quiet: one surface stays on the previous palette while the
		// rest of the screen moves, which looks like a rendering bug and is a data one.
		assertThatThrownBy(() -> admin.update("""
				INSERT INTO theme_packs (slug, name, family, description, palette)
				VALUES ('partial', 'Partial', 'MUTED', 'x', '{"canvas": "#FFFFFF"}'::jsonb)
				"""))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);

		assertThatThrownBy(() -> admin.update("""
				INSERT INTO theme_packs (slug, name, family, description, palette)
				VALUES ('loud', 'Loud', 'FLUORESCENT', 'x', ?::jsonb)
				""", completePalette()))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}

	// ---------------------------------------------------------------- helpers

	private void choose(String slug) throws Exception {
		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themePackSlug\":\"" + slug + "\"}"))
				.andExpect(status().isNoContent());
	}

	/** Every role the CHECK asks for, so a refusal in these tests is about the thing being tested. */
	private static String completePalette() {
		StringBuilder json = new StringBuilder("{");
		String[] tokens = {
			"canvas", "raised", "sunken", "hairline", "hairline-strong",
			"ink", "ink-secondary", "ink-muted", "ink-inverse",
			"accent-bg", "accent-border", "accent", "accent-hover", "accent-text", "focus-ring",
			"danger-bg", "danger", "info-bg", "info", "warning-bg", "warning",
			"success-bg", "success",
		};
		for (int i = 0; i < tokens.length; i++) {
			json.append(i == 0 ? "" : ",").append('"').append(tokens[i]).append("\":\"#123456\"");
		}
		return json.append('}').toString();
	}

	private static String bluePalette() {
		return completePalette().replace("\"accent\":\"#123456\"", "\"accent\":\"" + BLUE_ACCENT + "\"");
	}

	private String nameOf(String slug) {
		return admin.query("SELECT name FROM theme_packs WHERE slug = ?",
				rs -> rs.next() ? rs.getString(1) : null, slug);
	}

	private int count(String table) {
		Integer n = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return n == null ? 0 : n;
	}

	/**
	 * Runs a statement as the unprivileged application role, carrying one person's verified
	 * identity. The point of going round the application: the policy has to refuse this on its own,
	 * with no Java in the way.
	 */
	private void asUser(String uid, java.util.function.Consumer<JdbcTemplate> work) {
		org.springframework.jdbc.datasource.DriverManagerDataSource plain =
				new org.springframework.jdbc.datasource.DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		org.iskcon.kms.tenancy.TenantContext.setAuthLookupUid(uid);
		try {
			work.accept(new JdbcTemplate(new org.iskcon.kms.tenancy.TenantAwareDataSource(plain)));
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", role);
	}

	private void insertOperator(String uid) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, ?, 'Platform Operator', ?, '+919876500099', 'SUPER_ADMIN', 'ACTIVE')
				""", uid, uid + "@example.com");
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
