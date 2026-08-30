package org.iskcon.kms.shift;

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
 * The colour scheme a temple works in (V72).
 *
 * <p>There is very little of this feature on this side, and that is the design rather than an
 * omission: the themes live in {@code frontend/lib/theme-packs.ts} and this application stores an
 * opaque identifier. So what is worth asserting here is small and specific.
 *
 * <p><b>The choice belongs to the temple, not to the person.</b> An administrator picks, and the
 * cook who never opens Settings sees it. A kitchen where two people see two different colours is a
 * kitchen where they cannot describe a screen to each other over the noise of a Sunday feast. That
 * is the claim the whole feature rests on, and it is the first test below.
 *
 * <p><b>This side has no opinion about which themes exist.</b> Knowing would mean keeping a second
 * copy of the catalogue in step with the first. So an identifier it has never heard of is stored
 * without complaint and resolved to the default in the browser — while something that could not be
 * an identifier at all is refused at the boundary.
 */
@AutoConfigureMockMvc
@Import(TenantThemeIT.StubVerifierConfiguration.class)
class TenantThemeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		UUID templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		UUID templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-cook-a", "KITCHEN_STAFF");
		insertUser(templeB, "uid-admin-b", "TEMPLE_ADMIN");
		insertOperator("uid-operator");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("what the admin picks is what the cook sees, without the cook doing anything")
	void theChoiceReachesEverybody() throws Exception {
		signIn("uid-admin-a");
		choose("harbour-blue");

		// The point of the whole feature, and the reason the identifier rides on /whoami rather
		// than on a settings endpoint a cook is not allowed to call.
		signIn("uid-cook-a");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").value("harbour-blue"));
	}

	@Test
	@DisplayName("a temple that has never chosen says so, rather than guessing a default here")
	void nullUntilChosen() throws Exception {
		signIn("uid-cook-a");

		// Null rather than "temple-terracotta". Which pack is the default is the catalogue's
		// business, and the catalogue is on the other side — naming it here would be a second copy
		// of a decision, in the one place that cannot see whether it is still true.
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").doesNotExist());
	}

	@Test
	@DisplayName("a settings row that exists for some other reason, with no theme chosen")
	void settingsRowWithoutATheme() throws Exception {
		// The state that took the application down on 2026-08-30, and the reason it got through:
		// "has not chosen" has two shapes, and only one of them was tested. No row at all gives an
		// empty result. A row that exists because the temple once set some *other* preference,
		// with the theme still null, gives a result holding one null — which is what
		// `stream().findFirst()` cannot survive. Every temple that has ever opened settings is in
		// the second shape.
		signIn("uid-admin-a");
		mvc.perform(authed(put("/api/v1/settings/volunteer-broadcast-limit"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"limit\":5}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").doesNotExist());
		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.volunteerBroadcastDailyLimit").value(5))
				.andExpect(jsonPath("$.themeId").doesNotExist());

		// And the cook, because /whoami is what every session calls and what actually broke.
		signIn("uid-cook-a");
		mvc.perform(authed(get("/api/v1/whoami"))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("a platform operator belongs to no temple and has no colours of their own")
	void operatorHasNoTheme() throws Exception {
		signIn("uid-operator");

		// Without a special case: an operator carries no app.tenant_id, so the policy on
		// tenant_settings matches nothing and the read comes back empty on its own.
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").doesNotExist())
				.andExpect(jsonPath("$.themeId").doesNotExist());
	}

	@Test
	@DisplayName("one temple's colours are not another's")
	void perTemple() throws Exception {
		signIn("uid-admin-a");
		choose("harbour-blue");

		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").doesNotExist());
	}

	@Test
	@DisplayName("the settings screen reads back what the temple chose, and changing it replaces it")
	void settingsReadsBack() throws Exception {
		signIn("uid-admin-a");

		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").doesNotExist());

		choose("harbour-blue");
		choose("temple-terracotta");

		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.themeId").value("temple-terracotta"));
		// Replaced, not accumulated — one row per temple, however many times they change their mind.
		Integer rows = admin.queryForObject("SELECT count(*) FROM tenant_settings", Integer.class);
		org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1);
	}

	@Test
	@DisplayName("a theme this side has never heard of is stored rather than argued with")
	void unknownThemeIsStored() throws Exception {
		signIn("uid-admin-a");

		// Deliberate. Which themes exist is the frontend catalogue's business, and an identifier
		// that stops resolving — a pack removed rather than retired — is handled there, by falling
		// back to the default. Refusing it here would mean keeping a second copy of the catalogue.
		choose("a-pack-that-was-deleted");

		mvc.perform(authed(get("/api/v1/settings")))
				.andExpect(jsonPath("$.themeId").value("a-pack-that-was-deleted"));
	}

	@Test
	@DisplayName("something that could not be a theme at all is refused at the boundary")
	void malformedThemeIsRefused() throws Exception {
		signIn("uid-admin-a");

		for (String bad : new String[] {"Temple Terracotta", "temple_terracotta", "-leading",
				"trailing-", "double--hyphen", "with space", "ünicode"}) {
			mvc.perform(authed(put("/api/v1/settings/theme"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"themeId\":\"" + bad + "\"}"))
					.andExpect(status().isBadRequest());
		}

		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themeId\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a cook cannot choose the temple's colours")
	void cooksCannotChoose() throws Exception {
		signIn("uid-cook-a");

		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themeId\":\"harbour-blue\"}"))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- helpers

	private void choose(String themeId) throws Exception {
		mvc.perform(authed(put("/api/v1/settings/theme"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"themeId\":\"" + themeId + "\"}"))
				.andExpect(status().isNoContent());
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
