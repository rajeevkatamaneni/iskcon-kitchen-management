package org.iskcon.kms.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the database session scoping for the current thread of execution.
 *
 * <p>Set once per request by the authentication filter and read by {@link TenantAwareDataSource}
 * when a connection is handed out. Nothing else should write to it.
 *
 * <p>Deliberately never populated from a request parameter, header, or body — SYSTEM_DESIGN.md
 * §4 requires the tenant to come from the verified token only. Accepting it from the request
 * would let a caller choose which temple's data to read.
 */
public final class TenantContext {

	private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();
	private static final ThreadLocal<String> AUTH_LOOKUP_UID = new ThreadLocal<>();
	private static final ThreadLocal<String> CLAIM_CONTACT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(UUID tenantId) {
		CURRENT_TENANT.set(tenantId);
	}

	public static Optional<UUID> get() {
		return Optional.ofNullable(CURRENT_TENANT.get());
	}

	/**
	 * Permits reading exactly one user row — the one whose Firebase UID this is — before the
	 * tenant is known.
	 *
	 * <p>This exists to break a genuine chicken-and-egg: the user record is what tells us which
	 * tenant to scope to, but RLS would hide that record until the tenant is already set.
	 *
	 * <p>The escape is deliberately narrow. The policy matches on the UID itself rather than
	 * disabling isolation, so it exposes a single row, and only to a caller who already holds a
	 * Firebase token that Google verified for that exact UID. It cannot be used to enumerate
	 * users, and it grants no access to any other table.
	 */
	public static void setAuthLookupUid(String firebaseUid) {
		AUTH_LOOKUP_UID.set(firebaseUid);
	}

	public static Optional<String> getAuthLookupUid() {
		return Optional.ofNullable(AUTH_LOOKUP_UID.get());
	}

	/**
	 * Permits reading a not-yet-claimed user row by a Firebase-verified contact, before the tenant
	 * is known, so a provisioned account can be bound to the person's real Firebase uid on first
	 * sign-in.
	 *
	 * <p>A sibling of {@link #setAuthLookupUid} and just as narrow: the matching RLS policy exposes
	 * only rows still {@code pending:} whose email or phone equals this exact value, which the
	 * authentication filter sets only to a contact Firebase has verified, and only for the duration
	 * of a claim attempt.
	 */
	public static void setClaimContact(String contact) {
		CLAIM_CONTACT.set(contact);
	}

	public static Optional<String> getClaimContact() {
		return Optional.ofNullable(CLAIM_CONTACT.get());
	}

	public static void clearClaimContact() {
		CLAIM_CONTACT.remove();
	}

	/**
	 * Clears all scoping. Must run in a finally block at the end of every request — threads are
	 * pooled, and a leaked value would give the next request on this thread the previous
	 * request's access.
	 */
	public static void clear() {
		CURRENT_TENANT.remove();
		AUTH_LOOKUP_UID.remove();
		CLAIM_CONTACT.remove();
	}
}
