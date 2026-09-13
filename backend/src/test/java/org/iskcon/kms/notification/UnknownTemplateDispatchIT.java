package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.ops.OpsService;
import org.iskcon.kms.ops.TenantOps;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A queued notification naming a template this release no longer has (T-180).
 *
 * <p><strong>Why this exists.</strong> T-180 removed {@code SHIFT_REMINDER}. A notification row stores
 * its template as the constant's name in a text column, so removing the constant cannot remove a row
 * queued under it before the release. The dispatcher used to call {@code NotificationTemplate.valueOf}
 * on that text bare, which throws for a name it does not know. The job would then retry a row that can
 * never succeed and park it, and the row would sit PENDING for ever, which the ops failure list never
 * shows.
 *
 * <p>So the rule tested here: that one row is marked FAILED with a plain reason and its stored name is
 * logged, nothing is thrown, and the messages either side of it still send. The name used is the real
 * one, {@code SHIFT_REMINDER}, because that is the row that can actually exist.
 *
 * <p><strong>Same context as {@link NotificationIT}, on purpose.</strong> The annotations, the property
 * and the mail sender mock are copied exactly, because each is part of Spring's context cache key, and a
 * different set would start a whole second application context for three tests.
 */
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
		"kms.notifications.email.from=noreply@kms.test"})
class UnknownTemplateDispatchIT extends AbstractIntegrationTest {

	/** So the email leg can succeed without a relay; nothing leaves the building. */
	@org.springframework.boot.test.mock.mockito.MockBean
	private org.springframework.mail.javamail.JavaMailSender mailSender;

	private static final String RETIRED_NAME = "SHIFT_REMINDER";

	@Autowired
	private NotificationDispatcher dispatcher;

	@Autowired
	private OpsService opsService;

	private JdbcTemplate admin;
	private UUID temple;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		// A bare mock hands back a null message and the email adapter falls over on it.
		org.mockito.Mockito.when(mailSender.createMimeMessage())
				.thenAnswer(i -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		logs = new ListAppender<>();
		logs.start();
		dispatcherLogger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		dispatcherLogger().detachAppender(logs);
		TenantContext.clear();
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a row naming a removed template, queued between two good ones, fails with a plain reason and the two either side still send")
	void anUnknownTemplateBetweenTwoGoodRowsFailsAloneAndQuietly() {
		UUID before = queue("VOLUNTEER_SHIFT_REMINDER", "before@govinda.example");
		UUID retired = queue(RETIRED_NAME, "retired@govinda.example");
		UUID after = queue("VOLUNTEER_SHIFT_REMINDER", "after@govinda.example");

		// In queue order, one dispatch each, as three send jobs would run them. None may throw.
		for (UUID id : List.of(before, retired, after)) {
			dispatchAs(temple, id);
		}

		assertThat(statusOf(before)).isEqualTo("SENT");
		assertThat(statusOf(after)).as("the row after the unknown one is still sent").isEqualTo("SENT");
		assertThat(statusOf(retired)).isEqualTo("FAILED");

		List<Map<String, Object>> attempts = admin.queryForList(
				"SELECT channel, outcome, detail FROM notification_attempts WHERE notification_id = ?", retired);
		assertThat(attempts).as("one attempt on the record, carrying the reason").hasSize(1);
		assertThat(attempts.get(0).get("outcome")).isEqualTo("FAILED");
		assertThat(attempts.get(0).get("channel")).as("the row's preferred channel").isEqualTo("EMAIL");
		assertThat(attempts.get(0).get("detail")).isEqualTo(NotificationDispatcher.TEMPLATE_GONE);
		assertThat(NotificationDispatcher.TEMPLATE_GONE).doesNotContain(RETIRED_NAME).doesNotContain("valueOf");

		assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
				.as("the stored name is in the log, where somebody investigating will look")
				.anyMatch(line -> line.contains(RETIRED_NAME) && line.contains(retired.toString()));
	}

	@Test
	@DisplayName("a row failed that way is terminal: dispatching it again sends nothing and adds nothing, and the ops failure list shows it by its stored name")
	void failedThatWayIsTerminalAndListedAsItIs() {
		UUID retired = queue(RETIRED_NAME, "retired@govinda.example");
		dispatchAs(temple, retired);

		// What a job retry or a duplicate enqueue would do.
		dispatchAs(temple, retired);

		assertThat(statusOf(retired)).isEqualTo("FAILED");
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM notification_attempts WHERE notification_id = ?", Integer.class, retired))
				.as("no second attempt").isEqualTo(1);

		TenantOps ops = opsService.tenantOperations(temple);
		assertThat(ops.failedToday()).isEqualTo(1);
		assertThat(ops.recentFailures()).singleElement().satisfies(failure -> {
			assertThat(failure.id()).isEqualTo(retired);
			assertThat(failure.template()).isEqualTo(RETIRED_NAME);
		});
	}

	@Test
	@DisplayName("a known name resolves, and an unknown or missing one resolves to nothing rather than throwing")
	void templateNamedNeverThrows() {
		assertThat(NotificationDispatcher.templateNamed("VOLUNTEER_SHIFT_REMINDER"))
				.isEqualTo(NotificationTemplate.VOLUNTEER_SHIFT_REMINDER);
		assertThat(NotificationDispatcher.templateNamed(RETIRED_NAME)).isNull();
		assertThat(NotificationDispatcher.templateNamed("volunteer_shift_reminder"))
				.as("the stored form is the constant's name, not Meta's").isNull();
		assertThat(NotificationDispatcher.templateNamed(null)).isNull();
	}

	// ---------------------------------------------------------------------

	private UUID queue(String template, String email) {
		UUID id = UUID.randomUUID();
		admin.update("""
				INSERT INTO notifications (id, tenant_id, recipient_label, to_email, template, params,
						preferred_channel, status)
				VALUES (?, ?, 'Test Devotee', ?, ?,
						'{"title":"Kitchen seva","date":"12 August","time":"6:00 am","location":"Main kitchen"}'::jsonb,
						'EMAIL', 'PENDING')
				""", id, temple, email, template);
		return id;
	}

	private void dispatchAs(UUID tenantId, UUID notificationId) {
		TenantContext.set(tenantId);
		try {
			dispatcher.dispatch(notificationId);
		} finally {
			TenantContext.clear();
		}
	}

	private String statusOf(UUID id) {
		return admin.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
	}

	private static Logger dispatcherLogger() {
		return (Logger) LoggerFactory.getLogger(NotificationDispatcher.class);
	}
}
