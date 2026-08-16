package org.iskcon.kms.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a temple's own secrets are kept — its payment key secret, its webhook secret.
 *
 * <p>Deliberately not the database. What a temple pastes into Settings is a live credential against
 * their own money, and our schema is the wrong place for it: a dump would carry it, we would own the
 * encryption key, and the only record of who read it would be a table the application can rewrite.
 * Held in a secret manager instead, a database dump leaks nothing spendable, IAM decides who may
 * read, and every read is logged somewhere this application cannot reach.
 *
 * <p>The names are derived from the tenant id rather than stored, so nothing has to be read before a
 * temple can be erased, and orphans left by a failed deletion can always be found again.
 */
public interface TenantSecretStore {

	/** A secret a temple owns. The name is the enum, so a typo cannot open the wrong door. */
	enum Kind {
		/** The provider's API secret, used to open checkouts and read payment status. */
		PAYMENT_KEY_SECRET("payment-key-secret"),
		/** The shared secret a provider signs its webhooks with. */
		PAYMENT_WEBHOOK_SECRET("payment-webhook-secret");

		private final String suffix;

		Kind(String suffix) {
			this.suffix = suffix;
		}

		public String suffix() {
			return suffix;
		}
	}

	/** Stores (or replaces) one secret for one temple. */
	void put(UUID tenantId, Kind kind, String value);

	/** Reads one secret, or empty when the temple has never set it. */
	Optional<String> get(UUID tenantId, Kind kind);

	/**
	 * Erases every secret this temple owns.
	 *
	 * <p>Called after the temple's rows are gone, never before: an orphaned secret can be swept up
	 * later, whereas a live temple whose credentials were destroyed by a deletion that then rolled
	 * back cannot take a donation until somebody notices.
	 */
	void deleteAll(UUID tenantId);
}
