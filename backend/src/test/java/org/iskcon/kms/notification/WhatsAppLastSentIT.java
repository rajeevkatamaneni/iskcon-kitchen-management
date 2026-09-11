package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code tenant_settings.whatsapp_last_sent_at} (V123, T-136): what NULL means, who may write it, and
 * that one temple's sends are invisible to another.
 *
 * <p>Rajeev, 2026-09-10: the Send on WhatsApp button is shown "only after a message has actually
 * gone through it successfully", not merely configured. That makes this column the gate, and a gate
 * has to be tested against a real database — it is read through {@code tenant_settings}' Row-Level
 * Security policy, and mocking that would prove nothing.
 *
 * <p>No {@code @MockBean}, no {@code @Import}, no {@code @TestPropertySource}, so this class shares
 * the suite's default application context rather than caching one of its own.
 */
class WhatsAppLastSentIT extends AbstractIntegrationTest {

	@Autowired
	private TenantWhatsAppSettingsService settings;

	private JdbcTemplate admin;
	private UUID govinda;
	private UUID mayapur;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		govinda = temple("radha-govinda", "Sri Sri Radha Govinda Temple");
		mayapur = temple("mayapur", "Mayapur Temple");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM tenants");
	}

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	/** A temple with WhatsApp connected — credentials stored, number verified, nothing ever sent. */
	private void connectWhatsApp(UUID tenant) {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id,
						whatsapp_webhook_token, whatsapp_verified_at, whatsapp_templates_submitted_at,
						whatsapp_webhook_seen_at)
				VALUES (?, ?, ?, ?, now(), now(), now())
				""", tenant, "phone-" + tenant, "waba-" + tenant, "tok-" + tenant);
	}

	private Object lastSentOf(UUID tenant) {
		return admin.queryForMap(
				"SELECT whatsapp_last_sent_at FROM tenant_settings WHERE tenant_id = ?", tenant)
				.get("whatsapp_last_sent_at");
	}

	/**
	 * The whole point of the column, in one assertion.
	 *
	 * <p>This temple is connected in every sense the application could previously observe: its
	 * credentials reached Meta ({@code whatsapp_verified_at}), its templates were submitted
	 * ({@code whatsapp_templates_submitted_at}) and a signed callback has arrived
	 * ({@code whatsapp_webhook_seen_at}). Every one of those says "configured" and gating the button
	 * on any of them would defeat the ruling — so the answer here has to be no.
	 */
	@Test
	@DisplayName("a temple that has configured WhatsApp perfectly has still never sent anything")
	void configuredIsNotSent() {
		connectWhatsApp(govinda);

		TenantContext.set(govinda);
		assertThat(settings.hasEverSentSuccessfully()).isFalse();
		assertThat(lastSentOf(govinda)).isNull();
	}

	@Test
	@DisplayName("a temple with no settings row at all has never sent anything, and does not blow up")
	void noRowMeansNeverSent() {
		TenantContext.set(govinda);
		assertThat(settings.hasEverSentSuccessfully()).isFalse();
	}

	@Test
	@DisplayName("no tenant on the connection means no answer, not somebody else's answer")
	void noTenantMeansFalse() {
		connectWhatsApp(govinda);
		TenantContext.clear();

		assertThat(settings.hasEverSentSuccessfully()).isFalse();
	}

	@Test
	@DisplayName("a successful send stamps the date, and the temple can send from then on")
	void aSendStampsTheDate() {
		connectWhatsApp(govinda);

		TenantContext.set(govinda);
		settings.markMessageSent();

		assertThat(settings.hasEverSentSuccessfully()).isTrue();
		assertThat(lastSentOf(govinda)).isNotNull();
	}

	/**
	 * And it is one temple's fact, which is the database's job rather than the query's.
	 *
	 * <p>{@code markMessageSent} carries no tenant id at all — it scopes on
	 * {@code current_setting('app.tenant_id')}, the same way every other write in this service does,
	 * and the RLS policy on {@code tenant_settings} is what makes that safe. A send by one temple
	 * appearing to another would put the button in front of people whose WhatsApp has never worked,
	 * which is the defect the whole task exists to remove.
	 */
	@Test
	@DisplayName("one temple's send is invisible to another")
	void aSendIsNotSharedBetweenTemples() {
		connectWhatsApp(govinda);
		connectWhatsApp(mayapur);

		TenantContext.set(govinda);
		settings.markMessageSent();

		TenantContext.set(mayapur);
		assertThat(settings.hasEverSentSuccessfully()).isFalse();
		assertThat(lastSentOf(mayapur)).isNull();

		TenantContext.set(govinda);
		assertThat(settings.hasEverSentSuccessfully()).isTrue();
	}

	/**
	 * A second send moves the date rather than being ignored.
	 *
	 * <p>It is a "last", not a "first". Nothing depends on that today — the screen asks only whether
	 * the date is there at all — and it is stored that way so a later task can say "not for six
	 * months" without another migration.
	 */
	@Test
	@DisplayName("sending again moves the date forward")
	void sendingAgainMovesTheDate() throws Exception {
		connectWhatsApp(govinda);

		TenantContext.set(govinda);
		settings.markMessageSent();
		Object first = lastSentOf(govinda);

		Thread.sleep(10);
		settings.markMessageSent();

		assertThat(lastSentOf(govinda)).isNotEqualTo(first);
	}
}
