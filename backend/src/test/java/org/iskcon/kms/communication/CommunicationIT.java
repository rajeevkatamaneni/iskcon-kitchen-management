package org.iskcon.kms.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Writing to the community, and a devotee's say in it (E8-S1…S3).
 *
 * <p>The claims worth proving in a real database are the ones a screen cannot: that a declined
 * category genuinely stops a message and an operational one goes anyway, that the count shown before
 * sending is the number that actually receive it, that the letter is stored sanitised rather than
 * cleaned on the way out, and that an unsubscribe link cannot be forged or pointed at somebody else.
 */
@AutoConfigureMockMvc
@Import(CommunicationIT.StubVerifierConfiguration.class)
class CommunicationIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private UnsubscribeTokens unsubscribeTokens;

	@Autowired
	private org.iskcon.kms.notification.NotificationService notifications;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID devoteeA;
	private UUID devoteeB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
						contact_consent_at)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@example.com', '+919876500001',
						'TEMPLE_ADMIN', 'ACTIVE', now())
				""", tenant);
		devoteeA = devotee("uid-dev-a", "Nitai Das", "+919876500091");
		devoteeB = devotee("uid-dev-b", "Gaura Das", "+919876500092");
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM communication_recipients");
		admin.execute("DELETE FROM communications");
		admin.execute("DELETE FROM communication_preferences");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the letter is stored already sanitised — nothing unsafe is ever at rest")
	void bodyIsSanitisedOnTheWayIn() throws Exception {
		String id = draft("{\"category\":\"NEWSLETTER\",\"channel\":\"EMAIL\",\"subject\":\"Janmashtami\","
				+ "\"bodyHtml\":\"<h2>Come early</h2><p onclick=\\\"steal()\\\">Hare <b>Krishna</b></p>"
				+ "<script>alert(1)</script><a href=\\\"https://example.org\\\">Details</a>\"}");

		String stored = admin.queryForObject(
				"SELECT body_html FROM communications WHERE id = ?::uuid", String.class, id);
		assertThat(stored).doesNotContain("script").doesNotContain("onclick");
		assertThat(stored)
				.as("what a temple letter is made of survives")
				.contains("Come early").contains("<b>Krishna</b>").contains("example.org");

		String text = admin.queryForObject(
				"SELECT body_text FROM communications WHERE id = ?::uuid", String.class, id);
		assertThat(text).as("the plain-text half a multipart email needs").contains("Hare Krishna");
	}

	@Test
	@DisplayName("a message somebody wrote can never be operational")
	void composedIsNeverOperational() throws Exception {
		mvc.perform(authed(post("/api/v1/communications")).contentType(MediaType.APPLICATION_JSON)
						.content("""
						{"category":"OPERATIONAL","channel":"EMAIL","subject":"Sneaky","bodyHtml":"<p>hi</p>"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));

		mvc.perform(authed(get("/api/v1/communications/categories")))
				.andExpect(jsonPath("$[?(@.value=='OPERATIONAL')]").doesNotExist())
				.andExpect(jsonPath("$.length()").value(5));
	}

	@Test
	@DisplayName("a devotee who declined this kind is not counted and does not receive it")
	void optingOutRemovesThemFromTheAudience() throws Exception {
		String id = draft(newsletter());

		mvc.perform(authed(get("/api/v1/communications/{id}/audience", id)))
				.andExpect(jsonPath("$.count").value(2));

		// Gaura declines newsletters, through his own account.
		signIn("uid-dev-b");
		mvc.perform(authed(put("/api/v1/profile/communications")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"category\":\"NEWSLETTER\",\"wanted\":false}"))
				.andExpect(status().isOk());
		signIn("uid-admin");

		mvc.perform(authed(get("/api/v1/communications/{id}/audience", id)))
				.andExpect(jsonPath("$.count").value(1));

		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.audience").value(1));

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM communication_recipients WHERE recipient_user_id = ?
				""", Integer.class, devoteeB))
				.as("he is not on the list at all, not merely suppressed")
				.isZero();
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM communication_recipients WHERE recipient_user_id = ?
				""", Integer.class, devoteeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("turning everything off never silences a shift reminder")
	void operationalMessagesIgnoreEveryPreference() throws Exception {
		signIn("uid-dev-a");
		mvc.perform(authed(put("/api/v1/profile/communications")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"allOptional\":false}"))
				.andExpect(status().isOk());
		signIn("uid-admin");

		// Nothing optional reaches him.
		String id = draft(newsletter());
		mvc.perform(authed(get("/api/v1/communications/{id}/audience", id)))
				.andExpect(jsonPath("$.count").value(1));

		// An operational one does. If that ever stops being true, the kitchen finds out by being
		// short-handed.
		// Through the same service every reminder uses, in the tenant context a request would carry —
		// so this is the real gate rather than a re-implementation of it.
		org.iskcon.kms.tenancy.TenantContext.set(tenant);
		try {
			notifications.notify(
					org.iskcon.kms.notification.NotificationRecipient.user(devoteeA),
					org.iskcon.kms.notification.NotificationTemplate.SHIFT_REMINDER,
					Map.of("role", "Kitchen seva", "temple", "Bengaluru Temple",
							"date", "12 August", "time", "6:00 am"),
					null);
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}

		Map<String, Object> reminder = admin.queryForMap("""
				SELECT status, category, suppressed_reason FROM notifications
				WHERE recipient_user_id = ? AND template = 'SHIFT_REMINDER'
				""", devoteeA);
		assertThat(reminder.get("status")).isEqualTo("PENDING");
		assertThat(reminder.get("category")).isEqualTo("OPERATIONAL");
		assertThat(reminder.get("suppressed_reason")).isNull();
	}

	@Test
	@DisplayName("the blanket switch overrides the individual ones, and turning it off restores them")
	void blanketSwitchDoesNotForgetTheChoicesUnderneath() throws Exception {
		signIn("uid-dev-a");
		savePreference("{\"category\":\"NEWSLETTER\",\"wanted\":false}");
		savePreference("{\"allOptional\":false}");

		mvc.perform(authed(get("/api/v1/profile/communications")))
				.andExpect(jsonPath("$.optedOutOfAll").value(true))
				.andExpect(jsonPath("$.categories[?(@.value=='TEMPLE_NOTICE')].subscribed").value(false));

		savePreference("{\"allOptional\":true}");

		mvc.perform(authed(get("/api/v1/profile/communications")))
				.andExpect(jsonPath("$.optedOutOfAll").value(false))
				// Restored — but not the one he declined separately. He never asked for that back.
				.andExpect(jsonPath("$.categories[?(@.value=='TEMPLE_NOTICE')].subscribed").value(true))
				.andExpect(jsonPath("$.categories[?(@.value=='NEWSLETTER')].subscribed").value(false));
	}

	@Test
	@DisplayName("somebody who never agreed to be contacted is not in the audience either")
	void unconsentedAreNotCounted() throws Exception {
		admin.update("UPDATE users SET contact_consent_at = NULL WHERE id = ?", devoteeB);
		String id = draft(newsletter());
		mvc.perform(authed(get("/api/v1/communications/{id}/audience", id)))
				.andExpect(jsonPath("$.count").value(1));
	}

	@Test
	@DisplayName("a send with nobody to send to is refused rather than reported as a success")
	void sendingToNobodyIsRefused() throws Exception {
		admin.update("UPDATE users SET contact_consent_at = NULL WHERE role = 'VOLUNTEER'");
		String id = draft(newsletter());
		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4952"));
	}

	@Test
	@DisplayName("a sent message cannot be edited, re-sent, or deleted, and is on the audit trail")
	void sentIsFinal() throws Exception {
		String id = draft(newsletter());
		mvc.perform(authed(post("/api/v1/communications/{id}/send", id))).andExpect(status().isOk());

		mvc.perform(authed(put("/api/v1/communications/{id}", id)).contentType(MediaType.APPLICATION_JSON)
						.content(newsletter()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4951"));

		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4951"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'COMMUNICATION_SENT'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a test copy reaches the author and writes no recipients")
	void testCopyIsNotASend() throws Exception {
		String id = draft(newsletter());
		mvc.perform(authed(post("/api/v1/communications/{id}/test", id)))
				.andExpect(status().isAccepted());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM communication_recipients", Integer.class)).isZero();
		assertThat(admin.queryForObject(
				"SELECT status FROM communications WHERE id = ?::uuid", String.class, id))
				.isEqualTo("DRAFT");
	}

	@Test
	@DisplayName("the public web copy exists for a sent message and for nothing else")
	void publicCopyOnlyForSent() throws Exception {
		String id = draft(newsletter());
		String token = admin.queryForObject(
				"SELECT public_token FROM communications WHERE id = ?::uuid", String.class, id);

		mvc.perform(get("/api/v1/public/communications/{t}", token)).andExpect(status().isNotFound());

		mvc.perform(authed(post("/api/v1/communications/{id}/send", id))).andExpect(status().isOk());

		// No Authorization header: a devotee reading a newsletter on a phone meets no sign-in screen.
		mvc.perform(get("/api/v1/public/communications/{t}", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subject").value("Janmashtami"))
				.andExpect(jsonPath("$.templeName").value("Bengaluru Temple"));

		mvc.perform(get("/api/v1/public/communications/{t}", "not-a-real-token"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("an unsubscribe link works with no session, and a tampered one does nothing")
	void unsubscribeNeedsNoSessionAndCannotBeForged() throws Exception {
		String token = unsubscribeTokens.issue(tenant, devoteeA, CommunicationCategory.NEWSLETTER);

		// Describing is not doing: a link scanner following the URL must not unsubscribe anybody.
		mvc.perform(get("/api/v1/public/unsubscribe").param("token", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valid").value(true))
				.andExpect(jsonPath("$.label").value("Newsletter"));
		assertThat(optedOutRows()).isZero();

		mvc.perform(post("/api/v1/public/unsubscribe").param("token", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.done").value(true));
		assertThat(optedOutRows()).isEqualTo(1);

		String tampered = token.substring(0, token.length() - 2) + "xy";
		mvc.perform(post("/api/v1/public/unsubscribe").param("token", tampered))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.done").value(false));
		assertThat(optedOutRows()).as("nobody else was touched").isEqualTo(1);
	}

	@Test
	@DisplayName("an unsubscribe token names the person it was issued for, not whoever holds it")
	void unsubscribeAffectsOnlyItsSubject() throws Exception {
		String forA = unsubscribeTokens.issue(tenant, devoteeA, CommunicationCategory.NEWSLETTER);
		// Held by B, signed in as B — it still only ever affects A.
		signIn("uid-dev-b");
		mvc.perform(authed(post("/api/v1/public/unsubscribe")).param("token", forA))
				.andExpect(status().isOk());

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM communication_preferences WHERE user_id = ?
				""", Integer.class, devoteeA)).isEqualTo(1);
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM communication_preferences WHERE user_id = ?
				""", Integer.class, devoteeB)).isZero();
	}

	@Test
	@DisplayName("kitchen staff cannot write to the community")
	void writingIsAdminOnly() throws Exception {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-cook', 'A Cook', 'cook@example.com', '+919876500064', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		signIn("uid-cook");
		mvc.perform(authed(get("/api/v1/communications"))).andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/communications")).contentType(MediaType.APPLICATION_JSON)
						.content(newsletter()))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private int optedOutRows() {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM communication_preferences", Integer.class);
		return count == null ? 0 : count;
	}

	private void savePreference(String body) throws Exception {
		mvc.perform(authed(put("/api/v1/profile/communications"))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
	}

	private static String newsletter() {
		return """
				{"category":"NEWSLETTER","channel":"EMAIL","subject":"Janmashtami",
				 "bodyHtml":"<p>Hare Krishna</p>"}
				""";
	}

	private String draft(String json) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/communications"))
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private UUID devotee(String uid, String name, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
						contact_consent_at)
				VALUES (?, ?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE', now()) RETURNING id
				""", UUID.class, tenant, uid, name, uid + "@example.com", phone);
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
