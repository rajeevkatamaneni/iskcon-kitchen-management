package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole path: {@code notify()} queues a message, the background job picks it up, and it sends —
 * nothing inline on the calling thread. Quartz is enabled here (the base test excludes it).
 *
 * <p><strong>This class runs its own scheduler, under its own name, and that is the fix for a flake
 * (T-152), not a style choice.</strong> This comment used to say the property set matched {@code
 * BackgroundJobIT} so the two shared one scheduler-enabled context. They never did: the {@code
 * @MockBean} below and the {@code email.from} property are both part of Spring's context cache key,
 * so this class always built a second context, with a second clustered Quartz node — and both nodes
 * were called {@code kms-scheduler}, polling the same {@code qrtz_} tables in the same database.
 * Clustered Quartz treats every node with the same scheduler name as interchangeable, which in
 * production they are. Here they are not: the other node belongs to {@code BackgroundJobIT} and
 * {@code RecipeDocumentE2EIT}'s context, which has no mail sender and no from address.
 *
 * <p>What then happened, measured rather than supposed (the proof is docs/work/proof/T-152.md):
 * <ol>
 *   <li>{@code notify()} is transactional, and Quartz's Spring job store joins that transaction, so
 *       the send trigger is inserted uncommitted. Quartz nudges its own scheduler thread the moment
 *       the job is scheduled — before the commit. The thread wakes, finds no trigger it can see, and
 *       goes back to sleep for its idle wait: 30 seconds, randomised down to as little as 24.
 *   <li>The commit lands and nothing nudges anybody. Whichever node's sleep ends first runs the send.
 *   <li>If it is this class's node, the send is late: 24–30s, against what used to be a 20s await.
 *       That was the first way this test failed, and raising the await to 30s (fc03d6a) only moved
 *       it to the edge of the same window.
 *   <li>If it is the other context's node, the cascade runs with that context's beans: WhatsApp
 *       fails (no settings), SMS fails (no provider), and email fails with "no email sender is
 *       configured for this deployment" — and the notification ends FAILED. That was the second
 *       way, on CI run 34570919636, whose captured log shows the send completing 20 seconds after
 *       it was queued with "from address unset", a property this class sets.
 * </ol>
 *
 * <p>So two properties, one for each half. A scheduler name of its own puts this class's triggers
 * in rows no other node reads (Quartz partitions every table by scheduler name, and creates the lock
 * rows for a new name itself). And a one-second idle wait means the node that does own them finds
 * the committed trigger within a second instead of half a minute. Neither is a production setting:
 * in production every node is the same application, and the API — which is where most {@code
 * notify()} calls happen — runs no scheduler at all, so a send there is always found by the worker's
 * poll and never by a nudge.
 *
 * <p>Isolating this class by name also protects {@code BackgroundJobIT} in the other direction: its
 * parked and abandoned counters live in its own context's meter registry, and a probe job run by
 * this node would have counted somewhere it never looks.
 */
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"spring.quartz.auto-startup=true",
		"spring.quartz.scheduler-name=kms-scheduler-notification-e2e",
		"spring.quartz.properties.org.quartz.scheduler.idleWaitTime=1000",
		"kms.notifications.email.from=noreply@kms.test"})
class NotificationSendE2EIT extends AbstractIntegrationTest {

	/**
	 * How long the caller's transaction stays open after {@code notify()} returns. Long enough that
	 * the scheduler thread's nudge always lands while the trigger is still uncommitted — the ordering
	 * that caused the flake, forced on every run rather than left to luck. It is not contrived: most
	 * real callers call {@code notify()} inside a larger transaction that keeps working afterwards.
	 */
	private static final Duration CALLER_KEEPS_WORKING = Duration.ofSeconds(1);

	/** So the email leg can succeed without a relay; nothing leaves the building. */
	@org.springframework.boot.test.mock.mockito.MockBean
	private org.springframework.mail.javamail.JavaMailSender mailSender;

	@org.junit.jupiter.api.BeforeEach
	void givenAMailSenderThatAccepts() {
		// A bare mock hands back a null message and the adapter falls over on it; a real MimeMessage
		// with no session is enough to be addressed and handed back to a sender that does nothing.
		org.mockito.Mockito.when(mailSender.createMimeMessage())
				.thenAnswer(i -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
	}

	@Autowired
	private NotificationService notificationService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID user;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		user = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
						preferred_channel, contact_consent_at, consent_version)
				VALUES (?, 'uid-e2e', 'Test Devotee', 'e2e@govinda.example', '+919876500040',
						'VOLUNTEER', 'ACTIVE', 'WHATSAPP', now(), '2026-08-10')
				RETURNING id
				""", UUID.class, temple);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("notify() queues a message that the background worker then sends")
	void notifyQueuesAndTheWorkerSends() {
		UUID id;
		TenantContext.set(temple);
		try {
			id = new TransactionTemplate(transactionManager).execute(tx -> {
				UUID queued = notificationService.notify(
						NotificationRecipient.user(user),
						NotificationTemplate.SHIFT_REMINDER,
						Map.of("role", "cook", "temple", "Govinda", "date", "Sunday", "time", "9am"),
						null);
				try {
					Thread.sleep(CALLER_KEEPS_WORKING.toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return queued;
			});
		} finally {
			TenantContext.clear();
		}

		// 10s is ten times what the send needs once its own node polls every second. It came down
		// from 30 because time was never the problem: a send that is still PENDING here is one no
		// node noticed, and a FAILED one ran somewhere it should not have — either way the attempts
		// below say which, so the next failure explains itself instead of needing a re-run.
		await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
				assertThat(admin.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
						.as("notification status; attempts so far: %s", attemptsOf(id))
						.isEqualTo("SENT"));
	}

	/** Every channel tried, in order, with what it said — the far side of a failure, in the message. */
	private String attemptsOf(UUID id) {
		return admin.query("""
				SELECT channel, outcome, detail FROM notification_attempts
				WHERE notification_id = ? ORDER BY created_at
				""", (rs, rowNum) -> rs.getString("channel") + " " + rs.getString("outcome")
						+ (rs.getString("detail") == null ? "" : " (" + rs.getString("detail") + ")"), id)
				.toString();
	}
}
