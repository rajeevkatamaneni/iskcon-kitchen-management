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

	private static final ThreadLocal<Boolean> LIBRARY_LOAD = new ThreadLocal<>();

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

	/**
	 * Permits the recipe-library loader to read and write {@code master_recipes} with no signed-in
	 * person behind it.
	 *
	 * <p>The loader runs as a job off the same image, on a thread that is not a request thread, and
	 * the library belongs to no tenant and to no user — so neither the tenant context nor the auth
	 * escape above can carry it. V66 met the same problem with notices raised by automation and
	 * answered it by admitting one shape for a connection with no identity; this is the same answer
	 * with a narrower key.
	 *
	 * <p>Narrow in three ways. It reaches exactly one table. Every policy branch that honours it
	 * <em>also</em> requires {@code app.auth_uid} to be absent, so a signed-in caller could not use
	 * it even if the flag were somehow set on their thread. And it grants no DELETE: removing a
	 * recipe from the library stays an operator's act.
	 */
	public static void setLibraryLoad() {
		LIBRARY_LOAD.set(Boolean.TRUE);
	}

	public static boolean isLibraryLoad() {
		return Boolean.TRUE.equals(LIBRARY_LOAD.get());
	}

	public static void clearLibraryLoad() {
		LIBRARY_LOAD.remove();
	}

	public static void set(UUID tenantId) {
		CURRENT_TENANT.set(tenantId);
	}

	public static Optional<UUID> get() {
		return Optional.ofNullable(CURRENT_TENANT.get());
	}

	/**
	 * Permits reading every {@code users} row that carries this Firebase uid — one per temple the
	 * person belongs to — before the tenant is known, and for the rest of the request after it.
	 *
	 * <p>This exists to break a genuine chicken-and-egg: the user record is what tells us which
	 * tenant to scope to, but RLS would hide that record until the tenant is already set. The read
	 * policy on {@code users} (V2, re-created in V4) therefore admits a row when its temple is the
	 * request's temple <em>or</em> its {@code firebase_uid} equals {@code app.auth_uid}.
	 *
	 * <p><strong>What it exposes.</strong> Not a single row. Since V52 one person holds one
	 * {@code users} row per temple, and the escape shows <em>all</em> of them, at every temple. And
	 * it lasts the whole signed-in request: {@code AuthenticationFilter} sets it before the lookup
	 * and nothing clears it once the temple is chosen, only {@link #clear()} at the end of the
	 * request. So during a request signed in at temple A, a query on {@code users} sees temple A's
	 * rows plus the caller's own accounts at temples B and C. (V2's own comment on the policy says
	 * "exactly one row"; it was written before V52 and is left as it is, because an applied
	 * migration is checksummed by Flyway and must never be edited.)
	 *
	 * <p><strong>What it is for.</strong> Two reads legitimately want every membership: sign-in
	 * finding the person's accounts and choosing the one this request speaks for
	 * ({@code AuthenticationFilter} through {@code UserRepository.findAllByFirebaseUid}), and the
	 * temple switcher listing them ({@code WhoAmIController.temples()}).
	 *
	 * <p><strong>What it does not do.</strong> It grants no write: the insert, update and delete
	 * policies on {@code users} (V8) are temple-only, with no uid branch, so an own account elsewhere
	 * can be read but never changed. It cannot enumerate other people, because it matches the uid
	 * exactly and only a caller holding a Firebase token Google verified for that uid ever sets it.
	 * It reaches no other table.
	 *
	 * <p><strong>The rule it imposes (T-190).</strong> On every other table the policy alone confines
	 * a request to its temple; on {@code users} it does not. <em>Any read of {@code users} during a
	 * request must name the temple in its own SQL</em> ({@code tenant_id = app.tenant_id}, or a
	 * lookup by an id already known to be this temple's), unless it means to see every membership,
	 * as the two reads above do. A read that trusted RLS to supply the temple picked up the caller's
	 * other accounts: {@code OwnAccountsAtOtherTemplesIT} holds one test for each that was found, and
	 * {@code RowLevelSecurityIT} pins the visibility itself as deliberate.
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
		LIBRARY_LOAD.remove();
	}
}
