package org.iskcon.kms.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.TokenVerifier.VerifiedSubject;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Binds a person's real Firebase identity to a pending account on their first sign-in.
 *
 * <p>Provisioning (and later invites) create a user before that person authenticates, with a
 * placeholder {@code firebase_uid} of {@code pending:<uuid>}. When they first sign in, Firebase
 * issues a real uid that matches no row — {@link AuthenticationFilter} looks up strictly by uid
 * and finds nothing. This is the step that closes the gap: on that miss, if the token carries a
 * Firebase-verified contact, adopt the real uid onto the pending row whose email or phone matches.
 *
 * <p>The whole operation is careful about two things. <strong>Verification:</strong> a claim is
 * only attempted for a contact Firebase has actually verified — an email it reports verified, or a
 * phone number (whose mere presence on the token is proof of OTP). An unverified email is ignored,
 * so nobody can register {@code admin@temple.example} unverified and inherit an admin's pending
 * account. <strong>Ambiguity:</strong> if a verified contact matches more than one pending row it
 * is refused, not guessed — that only arises with a person pending at two temples, which the
 * single-uid schema cannot represent yet anyway.
 */
@Component
public class PendingAccountClaim {

	private static final Logger log = LoggerFactory.getLogger(PendingAccountClaim.class);

	private final UserRepository userRepository;
	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public PendingAccountClaim(
			UserRepository userRepository, JdbcTemplate jdbc, AuditService auditService) {
		this.userRepository = userRepository;
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Attempts to claim a pending account for the given verified subject.
	 *
	 * @return the now-linked user if a single pending account was claimed; empty if there was no
	 *     verified contact, no match, or an ambiguous match — in every one of which the caller
	 *     treats the person as having no account here.
	 */
	public Optional<User> attemptClaim(VerifiedSubject subject) {
		String contact = verifiedContact(subject);
		if (contact == null) {
			// Nothing the person has proven control of, so nothing to safely match against.
			return Optional.empty();
		}

		// Opens the narrow RLS window (V4) so a pending row matching this exact verified contact
		// is visible before any tenant is known. Cleared however this method exits.
		TenantContext.setClaimContact(contact);
		try {
			List<User> matches = userRepository.findClaimablePending(contact);

			if (matches.isEmpty()) {
				return Optional.empty();
			}
			if (matches.size() > 1) {
				// Same verified contact pending at more than one temple. Refuse rather than bind
				// the identity to an arbitrary one.
				log.warn("Refusing ambiguous account claim: {} pending rows match the contact",
						matches.size());
				return Optional.empty();
			}

			return adopt(matches.get(0), subject.uid());

		} finally {
			TenantContext.clearClaimContact();
		}
	}

	/**
	 * Adopts the real uid onto the pending row. The write goes through ordinary tenant isolation:
	 * we set the row's own tenant as context, so the unchanged write policy permits it — no write
	 * escape exists, and none is wanted.
	 */
	private Optional<User> adopt(User pending, String realUid) {
		TenantContext.set(pending.getTenantId());

		int adopted = jdbc.update(
				"UPDATE users SET firebase_uid = ?, updated_at = now() "
						+ "WHERE id = ? AND firebase_uid LIKE 'pending:%'",
				realUid, pending.getId());

		if (adopted != 1) {
			// Lost a race to a concurrent first sign-in, or the row was already claimed. Not an
			// error: the next request resolves the person by their real uid through the ordinary
			// lookup.
			return Optional.empty();
		}

		User claimed = userRepository.findById(pending.getId()).orElseThrow();

		AuthenticatedUser actor = new AuthenticatedUser(claimed);
		if (claimed.getTenantId() != null) {
			auditService.record(
					actor,
					AuditAction.ACCOUNT_CLAIMED,
					AuditEntityType.USER,
					claimed.getId(),
					Map.of("linked", false),
					Map.of("linked", true),
					"first sign-in bound this account to its Firebase identity");
		} else {
			// A platform super-admin belongs to no tenant, so their claim goes to the platform
			// audit log rather than a temple's (E1-S14).
			auditService.recordPlatform(
					actor,
					AuditAction.ACCOUNT_CLAIMED,
					AuditEntityType.USER,
					claimed.getId(),
					Map.of("linked", false),
					Map.of("linked", true),
					"first sign-in bound this platform operator to its Firebase identity");
		}

		return Optional.of(claimed);
	}

	/**
	 * The contact we may safely match on: a verified email (lower-cased to match how users are
	 * stored), else a phone number, else nothing.
	 */
	private String verifiedContact(VerifiedSubject subject) {
		if (subject.emailVerified() && hasText(subject.email())) {
			return subject.email().toLowerCase();
		}
		if (hasText(subject.phoneNumber())) {
			return subject.phoneNumber();
		}
		return null;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
