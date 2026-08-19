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
	private static final ThreadLocal<String> WEBHOOK_MESSAGE_ID = new ThreadLocal<>();
	private static final ThreadLocal<String> PAYMENT_WEBHOOK_TOKEN = new ThreadLocal<>();

	private static final ThreadLocal<String> WHATSAPP_WEBHOOK_TOKEN = new ThreadLocal<>();

	private static final ThreadLocal<String> PUBLIC_COMMUNICATION_TOKEN = new ThreadLocal<>();

	private TenantContext() {
	}

	/**
	 * Permits reading exactly one sent communication — the one at this address — with no tenant at
	 * all.
	 *
	 * <p>A newsletter's web copy is opened by people who are not signed in, and often not signed in
	 * anywhere: it is what WhatsApp links to, because Meta will not carry the letter itself. The
	 * matching policy (V60) admits a single SENT row whose unguessable token equals this exact value,
	 * so what it exposes is precisely what the reader already had in their hand — the link they were
	 * sent. It cannot enumerate, cannot reach a draft, and grants no write.
	 */
	public static void setPublicCommunicationToken(String token) {
		PUBLIC_COMMUNICATION_TOKEN.set(token);
	}

	public static Optional<String> getPublicCommunicationToken() {
		return Optional.ofNullable(PUBLIC_COMMUNICATION_TOKEN.get());
	}

	public static void clearPublicCommunicationToken() {
		PUBLIC_COMMUNICATION_TOKEN.remove();
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
	 * Permits reading the single notification a provider's message id belongs to, before any
	 * tenant is known, so the unauthenticated delivery webhook can find the message it is a status
	 * update for. Set by the webhook handler alone, only to an id from a signature-verified payload
	 * (see the escape in V7). As narrow as the others: it exposes at most one row, on an exact id
	 * match, and grants no writes.
	 */
	public static void setWebhookMessageId(String providerMessageId) {
		WEBHOOK_MESSAGE_ID.set(providerMessageId);
	}

	public static Optional<String> getWebhookMessageId() {
		return Optional.ofNullable(WEBHOOK_MESSAGE_ID.get());
	}

	public static void clearWebhookMessageId() {
		WEBHOOK_MESSAGE_ID.remove();
	}

	/**
	 * Permits reading the single temple a payment webhook token belongs to, before any tenant is
	 * known — and, unlike its siblings, before the payload's signature has been checked.
	 *
	 * <p>That order is forced: the signature can only be verified with the temple's own webhook
	 * secret, and we cannot know whose secret to fetch until we have resolved the token. It is safe
	 * because of what the row holds and what it does not. The provider, the key id (which is public
	 * — it is handed to the browser to open checkout), the token the caller just presented, and two
	 * timestamps. No secret is in it; the secrets live in Secret Manager, behind IAM. So a caller
	 * guessing tokens learns only whether a temple has configured payments, and the signature check
	 * that follows is still the whole of the trust.
	 */
	public static void setPaymentWebhookToken(String token) {
		PAYMENT_WEBHOOK_TOKEN.set(token);
	}

	public static Optional<String> getPaymentWebhookToken() {
		return Optional.ofNullable(PAYMENT_WEBHOOK_TOKEN.get());
	}

	public static void clearPaymentWebhookToken() {
		PAYMENT_WEBHOOK_TOKEN.remove();
	}

	/**
	 * The same escape for a WhatsApp callback, and safe for the same reasons.
	 *
	 * <p>Its own setting rather than sharing the payment one, so a token minted for one purpose can
	 * never satisfy a policy meant for the other. The row it exposes holds two Meta ids, the token
	 * just presented and two timestamps; the access token and app secret are in Secret Manager, and
	 * the signature check that follows remains the whole of the trust.
	 */
	public static void setWhatsAppWebhookToken(String token) {
		WHATSAPP_WEBHOOK_TOKEN.set(token);
	}

	public static Optional<String> getWhatsAppWebhookToken() {
		return Optional.ofNullable(WHATSAPP_WEBHOOK_TOKEN.get());
	}

	public static void clearWhatsAppWebhookToken() {
		WHATSAPP_WEBHOOK_TOKEN.remove();
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
		WEBHOOK_MESSAGE_ID.remove();
		PAYMENT_WEBHOOK_TOKEN.remove();
		WHATSAPP_WEBHOOK_TOKEN.remove();
	}
}
