package org.iskcon.kms.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * <p>T-084 added three more claims to the same method, all of them about what a <em>second</em>
 * press does. Two admins retrying at once must send one copy each and not two, which is a lock and
 * is proven here against a real second transaction rather than against the text of the SQL. A retry
 * must leave an audit entry, under an action of its own so nothing counting sends starts counting
 * resends. And "there is nothing to send again" must say which of its three quite different reasons
 * it means, rather than telling somebody a draft was fully delivered.
 *
 * <p>T-094 adds the case underneath all of them: a devotee the relay <em>threw</em> on. Their
 * recipient row was written only after {@code notify} returned, so there was no row — and a person
 * with no row is not a failed recipient, they are not a recipient at all. Nothing here could name
 * them, the retry could not reach them, the delivery screen did not list them, and
 * {@code audience_count} went on saying forty against thirty-nine rows. The relay is made to refuse
 * a copy the only way it can be refused for real, by the scheduler {@code notify} enqueues the send
 * on: what those tests exercise is the whole of the real path, including the transaction it runs in,
 * which is the half that broke. A spy stubbed over {@code NotificationService} would have proved
 * nothing, because a stubbed call never enters the transaction whose rollback is the defect.
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
		//
		// KMS-400142 rather than KMS-400138, changed by T-084 and the reason that code was split.
		// This is precisely the case the old single sentence got wrong: the copy this test just
		// queued has not been delivered, it is on its way, and "Every copy of this message was
		// delivered" told the sender the one thing they were asking about and had no way to check.
		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400142"));

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

	@Test
	@DisplayName("two admins retrying at once send one copy each, not two")
	void concurrentRetriesSendOneCopyEach() throws Exception {
		String id = sendNewsletter();
		markDelivery(unreachable, "FAILED", null);
		markDelivery(reached, "DELIVERED", null);
		markDelivery(declined, "DELIVERED", null);
		UUID failedNotification = notificationFor(id, unreachable);

		// Two real transactions, not two calls that happen to follow one another. Admin A is a
		// connection held open on the application's own unprivileged role, doing by hand exactly what
		// retryFailed does and in the same order; admin B is the endpoint itself, on another thread.
		// Anything less than this proves nothing: asserting that the SQL contains "FOR UPDATE" would
		// pass just as happily against a lock taken after the read, which is no lock at all.
		ExecutorService pool = Executors.newSingleThreadExecutor();
		MvcResult adminBs;
		boolean adminBGotPastTheLock;
		try (Connection adminA = appConnection()) {
			adminA.setAutoCommit(false);
			lockCommunication(adminA, id);

			Future<MvcResult> adminB = pool.submit(() ->
					mvc.perform(authed(post("/api/v1/communications/{id}/retry", id))).andReturn());

			// B is inside failedRecipients' transaction and cannot get past the lock. Without the
			// FOR UPDATE it sails through here, reads the same failed list A is about to act on, and
			// queues a copy of its own for a devotee who is already being written to.
			//
			// Whether it got through is recorded rather than asserted on the spot, deliberately, so
			// that the run reaches the count below either way: the harm this task exists to prevent
			// is a devotee receiving the same letter twice, and a negative control should say so in
			// those words rather than stopping at the lock that would have prevented it.
			adminBGotPastTheLock = true;
			try {
				adminB.get(3, TimeUnit.SECONDS);
			} catch (TimeoutException stillWaiting) {
				adminBGotPastTheLock = false;
			}

			requeueTheFailure(adminA, id);
			adminA.commit();
			adminBs = adminB.get(20, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		// The whole point, in one number: the devotee whose copy failed has the failure and exactly
		// one retry of it. Three is two admins each sending them the same letter.
		assertThat(notificationCount(unreachable))
				.as("one failure and one retry of it — never one letter sent to somebody twice")
				.isEqualTo(2);
		assertThat(adminBGotPastTheLock)
				.as("admin B waited for the row admin A was holding").isFalse();
		assertThat(adminBs.getResponse().getStatus())
				.as("the second retry is refused, not silently sent again").isEqualTo(409);
		assertThat(adminBs.getResponse().getContentAsString())
				.as("and refused with the in-flight sentence, because A's copy is on its way")
				.contains("KMS-400142");
		assertThat(notificationFor(id, unreachable))
				.as("and the recipient row names the copy that is actually on its way")
				.isNotEqualTo(failedNotification);
	}

	@Test
	@DisplayName("a retry is written to the audit log under its own action")
	void aRetryIsAudited() throws Exception {
		String id = sendNewsletter();
		markDelivery(unreachable, "FAILED", null);
		markDelivery(reached, "DELIVERED", null);
		markDelivery(declined, "DELIVERED", null);

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isOk());

		Map<String, Object> entry = admin.queryForMap("""
				SELECT action, entity_type, entity_id, after_state
				FROM audit_events WHERE action = 'COMMUNICATION_RETRIED'
				""");
		assertThat(entry.get("entity_id").toString()).isEqualTo(id);
		assertThat(entry.get("after_state").toString())
				.as("what a reader needs to know: which letter, to how many, and how many had failed")
				// Postgres renders jsonb with a space after the colon; this is the stored row verbatim.
				.contains("\"retried\": 1").contains("\"failed\": 1").contains("Janmashtami");

		// Its own action, and not filed under the one that means "this temple wrote to its community".
		// Anything counting COMMUNICATION_SENT as messages sent must not start counting retries.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'COMMUNICATION_SENT'",
				Integer.class)).isEqualTo(1);

		// And it is readable on the screen an admin actually opens.
		mvc.perform(authed(get("/api/v1/audit-events")).param("action", "COMMUNICATION_RETRIED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events[0].action").value("COMMUNICATION_RETRIED"))
				.andExpect(jsonPath("$.events[0].actorLabel").value(
						"Temple Admin <admin@example.com> (TEMPLE_ADMIN)"));
	}

	@Test
	@DisplayName("retrying a draft says it has not been sent, rather than that every copy arrived")
	void retryingADraftSaysItWasNeverSent() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/communications"))
						.contentType(MediaType.APPLICATION_JSON).content(newsletter()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = JSON.readTree(body).get("id").asText();

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400143"))
				.andExpect(jsonPath("$.message").value("This message hasn't been sent yet."));

		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class))
				.as("a draft is refused without a single message being queued").isEqualTo(0);
	}

	@Test
	@DisplayName("retrying a message still on its way says so, rather than claiming it was delivered")
	void retryingAnUndeliveredMessageSaysItIsStillOnItsWay() throws Exception {
		// Sent a moment ago and nothing has been dispatched yet: every copy is PENDING, which is
		// neither a failure nor a delivery. This is what a stale tab or a second admin hits.
		String id = sendNewsletter();

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400142"))
				.andExpect(jsonPath("$.message").value("No copy of this message has failed."))
				.andExpect(jsonPath("$.action").value("Some are still on their way. Check back shortly."));

		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class))
				.as("and nobody is written to twice while the first copies are in flight").isEqualTo(3);
	}

	@Test
	@DisplayName("a devotee the relay threw on is recorded, shown as failed, and reached by a retry")
	void aRefusedCopyIsRecordedAndReachedByARetry() throws Exception {
		// The second copy of the three, which is Nitai Das: the audience is ordered by name, and the
		// assertions below say so rather than trusting it.
		relayRefusesCopies(Set.of(2));

		String id = sendNewsletter();

		// The send stands. One devotee the relay would not take is not a reason to abandon the other
		// two — and before this it was: notify() is transactional, so the exception marked the send's
		// own transaction rollback-only and the commit failed with everything undone.
		assertThat(recipientCount(id)).as("one row per intended recipient").isEqualTo(3);
		assertThat(admin.queryForObject(
				"SELECT audience_count FROM communications WHERE id = ?::uuid", Integer.class, id))
				.as("audience_count and the recipient rows are written from the same list")
				.isEqualTo(3);

		// The refused one is present and names no copy, which is the durable record of a failure.
		assertThat(notificationFor(id, unreachable))
				.as("no copy of this letter was ever made for them").isNull();
		assertThat(notificationFor(id, reached)).isNotNull();
		assertThat(notificationFor(id, declined)).isNotNull();

		// And the screen says Failed rather than Queued — nothing is on its way to them, and telling
		// an admin otherwise is the lie this task existed to end.
		mvc.perform(authed(get("/api/v1/communications/{id}/deliveries", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[?(@.recipientName=='Nitai Das')].status").value("FAILED"))
				.andExpect(jsonPath("$[?(@.recipientName=='Gaura Das')].status").value("PENDING"))
				.andExpect(jsonPath("$[?(@.recipientName=='Yamuna Devi')].status").value("PENDING"));

		// The whole point: "sends it again to the people it failed for" reaches this person.
		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.retried").value(1));

		UUID copy = notificationFor(id, unreachable);
		assertThat(copy).as("a copy exists for them now, and the row names it").isNotNull();
		assertThat(statusOf(copy)).isEqualTo("PENDING");
		assertThat(notificationCount(unreachable))
				.as("exactly one copy — the refused attempt never became one").isEqualTo(1);
		assertThat(notificationCount(reached))
				.as("and nobody who already had one was written to again").isEqualTo(1);
		assertThat(notificationCount(declined)).isEqualTo(1);
		assertThat(recipientCount(id)).as("a retry re-points a row, never adds one").isEqualTo(3);
	}

	@Test
	@DisplayName("a send the relay refuses entirely is still recorded, and every copy is retryable")
	void aSendRefusedEntirelyIsStillRecordedInFull() throws Exception {
		relayRefusesCopies(Set.of(1, 2, 3));

		String id = sendNewsletter();

		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class))
				.as("not one copy was made").isEqualTo(0);
		assertThat(recipientCount(id))
				.as("and every devotee it was meant for is still written down").isEqualTo(3);

		mvc.perform(authed(get("/api/v1/communications/{id}/deliveries", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.recipientName=='Nitai Das')].status").value("FAILED"))
				.andExpect(jsonPath("$[?(@.recipientName=='Gaura Das')].status").value("FAILED"))
				.andExpect(jsonPath("$[?(@.recipientName=='Yamuna Devi')].status").value("FAILED"));

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.retried").value(3));

		assertThat(notificationCount(unreachable)).isEqualTo(1);
		assertThat(notificationCount(reached)).isEqualTo(1);
		assertThat(notificationCount(declined)).isEqualTo(1);
	}

	@Test
	@DisplayName("a sent message with no recipients says it reached nobody, not that it was never sent")
	void aSentMessageWithNoRecipientsSaysItReachedNobody() throws Exception {
		String id = sendNewsletter();

		// The state the code before T-094 left behind, and the state a message on staging is in right
		// now: marked SENT, with not one recipient row, because every queueing attempt threw.
		admin.update("DELETE FROM communication_recipients WHERE communication_id = ?::uuid", id);

		mvc.perform(authed(post("/api/v1/communications/{id}/retry", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400146"))
				.andExpect(jsonPath("$.message")
						.value("This message was sent, but no copy of it ever reached anybody."));

		// Why the count alone was not enough to decide with. KMS-400143 — "Send it first" — was what
		// this used to answer, and this is the door it sent the reader to: shut, and shut for a
		// reason. An error whose next step cannot be followed is worse than no next step.
		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400086"));
	}

	// ---------------------------------------------------------------------

	/**
	 * A second connection as the application's own role, tenant set exactly as
	 * {@code TenantAwareDataSource} sets it on checkout.
	 *
	 * <p>Unprivileged on purpose. A superuser connection bypasses RLS entirely, so a lock proven
	 * against one would say nothing about the lock the application actually takes.
	 */
	private Connection appConnection() throws SQLException {
		Connection connection =
				DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
		try (PreparedStatement statement =
				connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
			statement.setString(1, tenant.toString());
			statement.execute();
		}
		return connection;
	}

	/** The lock retryFailed takes, taken by hand so a second admin can be held behind it. */
	private void lockCommunication(Connection connection, String id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT id FROM communications WHERE id = ? FOR UPDATE")) {
			statement.setObject(1, UUID.fromString(id));
			try (ResultSet rows = statement.executeQuery()) {
				assertThat(rows.next()).as("the communication to lock").isTrue();
			}
		}
	}

	/**
	 * What {@code queueFor} does, on the held connection: a fresh copy for the failed devotee and the
	 * recipient row re-pointed at it. Written out rather than called, because the whole point is that
	 * it happens inside a transaction this test controls the commit of.
	 */
	private void requeueTheFailure(Connection connection, String id) throws SQLException {
		UUID copy;
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO notifications (tenant_id, recipient_user_id, recipient_label, to_phone,
						to_email, template, params, preferred_channel, category, status)
				SELECT n.tenant_id, n.recipient_user_id, n.recipient_label, n.to_phone, n.to_email,
						n.template, n.params, n.preferred_channel, n.category, 'PENDING'
				FROM notifications n
				JOIN communication_recipients r ON r.notification_id = n.id
				WHERE r.communication_id = ? AND r.recipient_user_id = ?
				RETURNING id
				""")) {
			statement.setObject(1, UUID.fromString(id));
			statement.setObject(2, unreachable);
			try (ResultSet rows = statement.executeQuery()) {
				assertThat(rows.next()).as("a copy to re-queue").isTrue();
				copy = rows.getObject(1, UUID.class);
			}
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE communication_recipients SET notification_id = ?
				WHERE communication_id = ? AND recipient_user_id = ?
				""")) {
			statement.setObject(1, copy);
			statement.setObject(2, UUID.fromString(id));
			statement.setObject(3, unreachable);
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
	}

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

	/**
	 * Makes the relay refuse the numbered copies of this test's send, counting every copy the run
	 * asks it for.
	 *
	 * <p>Refused at the scheduler, because that is where a copy really can be refused: {@code notify}
	 * writes the notification row and then enqueues its send, and a scheduler that throws becomes the
	 * {@code KMS-500001} that {@code queueFor} catches — the same exception, from inside the same
	 * transaction, as a relay client that will not take a message. Stubbing {@code NotificationService}
	 * itself would have been easier and would have proved nothing: a stubbed call never enters the
	 * transaction whose rollback was half the defect, so the test would have passed against the broken
	 * code as happily as against this.
	 */
	private void relayRefusesCopies(Set<Integer> ordinals) throws SchedulerException {
		AtomicInteger asked = new AtomicInteger();
		doAnswer(invocation -> {
			if (ordinals.contains(asked.incrementAndGet())) {
				throw new SchedulerException("the relay would not take this copy");
			}
			return new Date();
		}).when(scheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
	}

	private int recipientCount(String communicationId) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM communication_recipients WHERE communication_id = ?::uuid",
				Integer.class, communicationId);
		return count == null ? 0 : count;
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
