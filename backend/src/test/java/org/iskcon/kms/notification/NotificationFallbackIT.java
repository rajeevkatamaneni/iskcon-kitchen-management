package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * The fallback cascade, with WhatsApp forced to fail (as staging can force it) so the message falls
 * through to SMS — and both attempts are recorded.
 */
@TestPropertySource(properties = "kms.notifications.dev.fail-channels=WHATSAPP")
@org.springframework.test.context.TestPropertySource(properties = {
		"kms.notifications.email.from=noreply@kms.test"})
class NotificationFallbackIT extends AbstractIntegrationTest {

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
	private NotificationDispatcher dispatcher;

	private JdbcTemplate admin;
	private UUID temple;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("WhatsApp failing falls through to email, and every attempt is on the record")
	void fallsBackToEmailRecordingEveryAttempt() {
		UUID id = UUID.randomUUID();
		admin.update("""
				INSERT INTO notifications (id, tenant_id, recipient_label, to_phone, to_email,
						template, preferred_channel, status)
				VALUES (?, ?, 'Vendor +919876500030', '+919876500030', 'vendor@example.com',
						'PO_DELIVERY', 'WHATSAPP', 'PENDING')
				""", id, temple);

		TenantContext.set(temple);
		try {
			dispatcher.dispatch(id);
		} finally {
			TenantContext.clear();
		}

		assertThat(admin.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id))
				.isEqualTo("SENT");
		assertThat(admin.queryForObject("SELECT final_channel FROM notifications WHERE id = ?", String.class, id))
				.as("WhatsApp could not, SMS has no provider, so email is what delivered it")
				.isEqualTo("EMAIL");

		List<Map<String, Object>> attempts = admin.queryForList(
				"SELECT channel, outcome FROM notification_attempts WHERE notification_id = ? "
						+ "ORDER BY created_at", id);
		// Every attempt on the record, including the two that could not: a delivery history that
		// showed only the successful hop would hide which channels this temple cannot actually use.
		assertThat(attempts).hasSize(3);
		assertThat(attempts.get(0)).containsEntry("channel", "WHATSAPP").containsEntry("outcome", "FAILED");
		assertThat(attempts.get(1)).containsEntry("channel", "SMS").containsEntry("outcome", "FAILED");
		assertThat(attempts.get(2)).containsEntry("channel", "EMAIL").containsEntry("outcome", "SENT");
	}
}
