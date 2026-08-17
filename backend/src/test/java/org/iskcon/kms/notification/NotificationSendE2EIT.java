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

/**
 * The whole path: {@code notify()} queues a message, the background job picks it up, and it sends —
 * nothing inline on the calling thread. Quartz is enabled here (the base test excludes it); the
 * property set matches {@code BackgroundJobIT} so the two share one scheduler-enabled context.
 */
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"spring.quartz.auto-startup=true",
		"kms.notifications.email.from=noreply@kms.test"})
class NotificationSendE2EIT extends AbstractIntegrationTest {

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
			id = notificationService.notify(
					NotificationRecipient.user(user),
					NotificationTemplate.SHIFT_REMINDER,
					Map.of("role", "cook", "temple", "Govinda", "date", "Sunday", "time", "9am"),
					null);
		} finally {
			TenantContext.clear();
		}

		// 30s, not 20: the Quartz store is clustered, and trigger acquisition can lag under CI load
		// (slower CPU, DB lock contention with the other scheduler-enabled E2E tests in this context).
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
				assertThat(admin.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
						.isEqualTo("SENT"));
	}
}
