package org.iskcon.kms.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Whose merchant account a donation is collected into (E7).
 *
 * <p>The question this settles is the one that matters most about multi-tenancy and money: a gift
 * given to one temple must never be created against another temple's gateway. It also fixes what
 * happens to a temple that has configured nothing — it falls back to the platform gateway, which in
 * a deployment without credentials takes no money at all, so the donation is visibly unconfirmed
 * rather than quietly landing somewhere it does not belong.
 */
@Import(PaymentGatewayResolverIT.RecordingFactoryConfiguration.class)
class PaymentGatewayResolverIT extends AbstractIntegrationTest {

	@Autowired
	private PaymentGatewayResolver resolver;

	@Autowired
	private TenantSecretStore secrets;

	private JdbcTemplate admin;
	private UUID bengaluru;
	private UUID mysore;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		bengaluru = tenant("radha-govinda", "Bengaluru Temple");
		mysore = tenant("iskcon-mysore", "Mysore Temple");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		secrets.deleteAll(bengaluru);
		secrets.deleteAll(mysore);
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("each temple collects into its own account, never into another temple's")
	void eachTempleGetsItsOwnGateway() {
		configure(bengaluru, "rzp_test_bengaluru", "bengaluru-secret");
		configure(mysore, "rzp_test_mysore", "mysore-secret");

		TenantContext.set(bengaluru);
		assertThat(resolver.forCurrentTenant().publicKey()).isEqualTo("rzp_test_bengaluru");

		TenantContext.set(mysore);
		assertThat(resolver.forCurrentTenant().publicKey()).isEqualTo("rzp_test_mysore");
	}

	@Test
	@DisplayName("a temple that has set nothing up falls back rather than borrowing someone's account")
	void anUnconfiguredTempleFallsBack() {
		configure(bengaluru, "rzp_test_bengaluru", "bengaluru-secret");

		TenantContext.set(mysore);
		// The platform default, which without credentials is the stub — it takes no money.
		assertThat(resolver.forCurrentTenant().name()).isEqualTo("stub");
	}

	@Test
	@DisplayName("a temple whose secret has gone missing falls back instead of failing the gift")
	void aMissingSecretDoesNotThrowAtTheDonor() {
		// Configured, but the secret is not in the store — a half-migration, or a manual deletion.
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, payment_provider, payment_key_id, payment_webhook_token)
				VALUES (?, 'RAZORPAY', 'rzp_test_orphan', 'token-orphan')
				""", bengaluru);

		TenantContext.set(bengaluru);
		assertThat(resolver.forCurrentTenant().name()).isEqualTo("stub");
	}

	@Test
	@DisplayName("rotating the key gives the next donation a client built from the new one")
	void rotatingTheKeyIsPickedUp() {
		configure(bengaluru, "rzp_test_first", "first-secret");
		TenantContext.set(bengaluru);
		assertThat(resolver.forCurrentTenant().publicKey()).isEqualTo("rzp_test_first");

		admin.update("UPDATE tenant_settings SET payment_key_id = 'rzp_test_second' WHERE tenant_id = ?",
				bengaluru);
		secrets.put(bengaluru, TenantSecretStore.Kind.PAYMENT_KEY_SECRET, "second-secret");

		// Cached by key id, so a rotation is picked up without anything being invalidated by hand.
		assertThat(resolver.forCurrentTenant().publicKey()).isEqualTo("rzp_test_second");
	}

	// ---- helpers ----------------------------------------------------------

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void configure(UUID tenantId, String keyId, String keySecret) {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, payment_provider, payment_key_id, payment_webhook_token)
				VALUES (?, 'RAZORPAY', ?, ?)
				""", tenantId, keyId, "token-" + keyId);
		secrets.put(tenantId, TenantSecretStore.Kind.PAYMENT_KEY_SECRET, keySecret);
	}

	/** Builds a gateway that reports its key id, without opening a connection to anyone. */
	static class RecordingFactory implements PaymentGatewayFactory {
		@Override
		public String provider() {
			return "RAZORPAY";
		}

		@Override
		public PaymentGateway forCredentials(String keyId, String keySecret) {
			return new PaymentGateway() {
				@Override
				public String name() {
					return "razorpay";
				}

				@Override
				public String publicKey() {
					return keyId;
				}

				@Override
				public PaymentOrder createOrder(long amountMinorUnits, String currency, String receipt,
						Map<String, String> notes) {
					return new PaymentOrder("order_" + keyId, amountMinorUnits, currency);
				}

				@Override
				public PaymentStatus fetchPaymentStatus(String paymentId) {
					return PaymentStatus.UNKNOWN;
				}

				@Override
				public java.util.Optional<CapturedPayment> findCapturedPayment(String orderId) {
					return java.util.Optional.empty();
				}

				@Override
				public SubscriptionResult createSubscription(String frequency, long amountMinorUnits,
						String currency, Map<String, String> notes) {
					return new SubscriptionResult("sub_" + keyId, null);
				}

				@Override
				public void cancelSubscription(String subscriptionId) {
					// nothing to cancel in a test double
				}
			};
		}
	}

	static class RecordingFactoryConfiguration {
		/** Ahead of the real Razorpay factory, which would open an HTTP client to a made-up key. */
		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		RecordingFactory recordingFactory() {
			return new RecordingFactory();
		}
	}
}
