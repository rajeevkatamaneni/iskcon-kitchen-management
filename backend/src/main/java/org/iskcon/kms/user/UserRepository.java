package org.iskcon.kms.user;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	/**
	 * Every membership one person holds, oldest first — a person may belong to several temples
	 * (V52), and the oldest is the one they joined first, which is their default.
	 *
	 * <p>Uses a native query because it runs <em>before</em> the tenant context is established:
	 * resolving the user is how we learn which tenant to set, and ordinary RLS-filtered access would
	 * return nothing at that point. It relies on the {@code app.auth_uid} escape instead (V2, see
	 * {@code TenantContext.setAuthLookupUid}). Safe because the uid comes from a token already
	 * verified against Google's public keys, and the escape exposes only rows carrying that exact uid.
	 *
	 * <p><strong>There is deliberately no single-row lookup by uid alone (T-191).</strong> One used to
	 * sit here returning {@code Optional<User>}. For a person with accounts at two temples it would
	 * throw on a non-unique result, because during a signed-in request the escape shows every one of
	 * their accounts, not only this temple's. It had no caller in the application, only tests, which
	 * is exactly how the next caller would have found it. A caller wanting one account filters this
	 * list by temple, or looks the account up by its id.
	 */
	@Query(value = """
			SELECT * FROM users
			WHERE firebase_uid = :firebaseUid
			ORDER BY created_at
			""", nativeQuery = true)
	List<User> findAllByFirebaseUid(@Param("firebaseUid") String firebaseUid);

	/**
	 * Finds not-yet-claimed accounts whose email or phone matches a Firebase-verified contact, so
	 * a first sign-in can bind the real uid onto the right pending row.
	 *
	 * <p>Runs before the tenant is known and so relies on the {@code app.claim_contact} RLS escape
	 * (V4): the database only returns a row when {@code app.claim_contact} is set to that row's own
	 * email or phone, which the authentication filter does only for a verified contact. The WHERE
	 * here mirrors the policy so the query is legible on its own; RLS is what enforces it.
	 *
	 * <p>Returns a list on purpose: more than one match means an ambiguous contact (the same
	 * person pending at two temples), which the caller refuses rather than guessing.
	 */
	@Query(value = """
			SELECT * FROM users
			WHERE firebase_uid LIKE 'pending:%'
			  AND (email = :contact OR phone = :contact)
			""", nativeQuery = true)
	List<User> findClaimablePending(@Param("contact") String contact);
}
