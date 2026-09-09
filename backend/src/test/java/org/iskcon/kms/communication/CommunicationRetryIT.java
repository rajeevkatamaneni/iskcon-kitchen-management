package org.iskcon.kms.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sending a message again to the addresses it failed for, and to nobody else (B6, T-015).
 *
 * <p>The claim this exists to prove is not "the retry works" — it is the far narrower one that a
 * retry <b>reaches the people it says it reached</b>. The defect it was built around is silent:
 * {@code queueFor()}'s insert used to end {@code ON CONFLICT … DO NOTHING}, so a retry created a
 * fresh notification, failed to attach it to the recipient row that already existed, and answered
 * with a cheerful count while the screen went on saying <i>Failed</i> beside every one of those
 * names. Nothing threw, nothing logged, and the sender walked away believing forty people had been
 * written to. So every assertion below is about the <b>recipient row's link</b> and about the
 * <b>untouched</b> recipients, never merely about a status code.
 *
 * <p>It imports {@link CommunicationIT.StubVerifierConfiguration} rather than declaring a stub of
 * its own on purpose: {@code @Import} is part of Spring's test-context cache key, so a second,
 * identical configuration class here would build and cache a whole second application context for
 * no gain. Sharing the one class shares the one context.
 */
@AutoConfigureMockMvc
@Import(CommunicationIT.StubVerifierConfiguration.class)
class CommunicationRetryIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private CommunicationIT.StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID unreachable;
	private UUID reached;
	private UUID declined;

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
		unreachable = devotee("uid-dev-a", "Nitai Das", "+919876500091");
		reached = devotee("uid-dev-b", "Gaura Das", "+919876500092");
		declined = devotee("uid-dev-c", "Yamuna Devi", "+919876500093");
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
	@DisplayName("a retry reaches the ones it failed for, and leaves everybody else alone")
	void retryTouchesOnlyTheFailures() throws Exception {
		String id = sendNewsletter();

		// Three outcomes, which is the whole point: one failure, one that arrived, and one the
		// system deliberately did not send because she had said not to.
		markDelivery(unreachable, "FAILED", null);
		markDelivery(reached, "DELIVERED", null);
		markDelivery(declined, "SUPPRESSED", "OPTED_OUT");

		UUID failedNotification = notificationFor(id, unreachable);
		UUID reachedNotification = notificationFor(id, reached);
		UUID declinedNotification = notificationFor(id, declined);

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.retried").value(1));

		// The failure now points at a *different* notification. This is the assertion the old
		// DO NOTHING failed: it queued a new message and left the row pointing at the old one.
		UUID afterFailed = notificationFor(id, unreachable);
		assertThat(afterFailed)
				.as("the failed recipient is attached to the copy that was just queued for them")
				.isNotEqualTo(failedNotification);
		assertThat(statusOf(afterFailed)).isEqualTo("PENDING");

		// And what the screen reads is the new one, so "Failed" stops being shown for somebody who
		// has just been written to.
		mvc.perform(authed(get("/api/v1/communications/{id}/deliveries", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.recipientName=='Nitai Das')].status").value("PENDING"))
				.andExpect(jsonPath("$[?(@.recipientName=='Gaura Das')].status").value("DELIVERED"));

		// Nobody else moved. Not the row, and not the number of copies they have been sent — a
		// devotee who read the letter this morning must not receive it twice.
		assertThat(notificationFor(id, reached))
				.as("the delivered recipient is untouched").isEqualTo(reachedNotification);
		assertThat(notificationFor(id, declined))
				.as("a suppressed recipient is a decision, not a failure").isEqualTo(declinedNotification);
		assertThat(statusOf(declinedNotification)).isEqualTo("SUPPRESSED");
		assertThat(notificationCount(reached))
				.as("no second copy was queued for somebody who already had one").isEqualTo(1);
		assertThat(notificationCount(declined)).isEqualTo(1);
		assertThat(notificationCount(unreachable))
				.as("the failed attempt is kept and the retry added to it").isEqualTo(2);

		// One row per person per message still holds — a retry re-points a row, never adds one.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM communication_recipients WHERE communication_id = ?::uuid",
				Integer.class, id)).isEqualTo(3);
	}

	@Test
	@DisplayName("retrying a message that reached everybody is refused rather than reported as a send")
	void nothingToRetryIsRefused() throws Exception {
		String id = sendNewsletter();
		markDelivery(unreachable, "DELIVERED", null);
		markDelivery(reached, "SENT", null);
		markDelivery(declined, "SUPPRESSED", "OPTED_OUT");

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400138"));

		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class))
				.as("a refusal sends nothing at all").isEqualTo(3);
	}

	@Test
	@DisplayName("a second retry, with the first still on its way, is refused rather than sent twice")
	void aRetryInFlightIsNotRetriedAgain() throws Exception {
		String id = sendNewsletter();
		markDelivery(unreachable, "FAILED", null);
		markDelivery(reached, "DELIVERED", null);
		markDelivery(declined, "DELIVERED", null);

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.retried").value(1));

		// The re-queued copy is PENDING, not FAILED. Pressing the button twice must not send it
		// twice — the second press has nothing that has failed to act on.
		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400138"));

		assertThat(notificationCount(unreachable)).isEqualTo(2);
	}

	@Test
	@DisplayName("a retry cannot change a word of the letter it is resending")
	void theLetterStaysFrozen() throws Exception {
		String id = sendNewsletter();
		markDelivery(unreachable, "FAILED", null);
		markDelivery(reached, "DELIVERED", null);
		markDelivery(declined, "DELIVERED", null);

		Map<String, Object> before = letter(id);

		// The retry endpoint takes no body. A caller that sends one anyway changes nothing, because
		// the message is read back out of the row rather than off the request.
		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
						{"category":"NEWSLETTER","channel":"EMAIL","subject":"Rewritten",
						 "bodyHtml":"<p>Something else entirely</p>"}
						"""))
				.andExpect(status().isOk());

		assertThat(letter(id)).as("subject, body and status are exactly as they were").isEqualTo(before);

		// And the ordinary edit path is refused after a retry exactly as it was before one — the
		// draft guard is untouched by any of this.
		mvc.perform(authed(put("/api/v1/communications/{id}", id))
						.contentType(MediaType.APPLICATION_JSON).content(newsletter()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400086"));

		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400086"));

		// The copy that was queued by the retry carries the original subject, not the attempted one.
		assertThat(admin.queryForObject("""
				SELECT params->>'subject' FROM notifications WHERE id = ?
				""", String.class, notificationFor(id, unreachable)))
				.isEqualTo("Janmashtami");
	}

	@Test
	@DisplayName("kitchen staff cannot retry a message either")
	void retryingIsAdminOnly() throws Exception {
		String id = sendNewsletter();
		markDelivery(unreachable, "FAILED", null);

		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-cook', 'A Cook', 'cook@example.com', '+919876500064', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		signIn("uid-cook");
		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	/** Writes a newsletter and sends it, so there are three recipient rows to work with. */
	private String sendNewsletter() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/communications"))
						.contentType(MediaType.APPLICATION_JSON).content(newsletter()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = JSON.readTree(body).get("id").asText();

		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.audience").value(3));
		return id;
	}

	/**
	 * What a provider webhook would have made of one person's copy.
	 *
	 * <p>Set directly, because the alternative is a real WhatsApp failure and there is no such thing
	 * in a test. The states written here are exactly the ones {@code notifications_status_valid}
	 * permits, so nothing is being invented.
	 */
	private void markDelivery(UUID userId, String status, String suppressedReason) {
		int rows = admin.update("""
				UPDATE notifications SET status = ?, suppressed_reason = ?, updated_at = now()
				WHERE id = (SELECT notification_id FROM communication_recipients
				            WHERE recipient_user_id = ?)
				""", status, suppressedReason, userId);
		assertThat(rows).as("a recipient row to mark").isEqualTo(1);
	}

	private UUID notificationFor(String communicationId, UUID userId) {
		return admin.queryForObject("""
				SELECT notification_id FROM communication_recipients
				WHERE communication_id = ?::uuid AND recipient_user_id = ?
				""", UUID.class, communicationId, userId);
	}

	private String statusOf(UUID notificationId) {
		return admin.queryForObject(
				"SELECT status FROM notifications WHERE id = ?", String.class, notificationId);
	}

	private int notificationCount(UUID userId) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, userId);
		return count == null ? 0 : count;
	}

	/** The words themselves, read back out of the row — the thing a retry must never change. */
	private Map<String, Object> letter(String id) {
		return admin.queryForMap("""
				SELECT subject, body_html, body_text, status, audience_count
				FROM communications WHERE id = ?::uuid
				""", id);
	}

	private static String newsletter() {
		return """
				{"category":"NEWSLETTER","channel":"EMAIL","subject":"Janmashtami",
				 "bodyHtml":"<p>Hare Krishna</p>"}
				""";
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
}
