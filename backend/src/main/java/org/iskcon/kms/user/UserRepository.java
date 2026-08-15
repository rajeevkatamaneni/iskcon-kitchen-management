package org.iskcon.kms.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	/**
	 * Looks up a user by their Firebase identity.
	 *
	 * <p>Uses a native query with an explicit RLS bypass consideration: this runs *before* the
	 * tenant context is established, since resolving the user is how we learn which tenant to
	 * set. Ordinary RLS-filtered access would return nothing at that point — a chicken-and-egg
	 * that would make login impossible.
	 *
	 * <p>Safe because the lookup is by Firebase UID, which the caller cannot forge: it comes
	 * from a token already verified against Google's public keys. Knowing a UID reveals nothing
	 * and grants nothing on its own.
	 */
	@Query(value = """
			SELECT * FROM users
			WHERE firebase_uid = :firebaseUid
			""", nativeQuery = true)
	Optional<User> findByFirebaseUid(@Param("firebaseUid") String firebaseUid);

	/**
	 * Every membership one person holds, oldest first — a person may belong to several temples
	 * (V52), and the oldest is the one they joined first, which is their default.
	 *
	 * <p>Same safety as above: the lookup is by a uid that came from a verified token, and the RLS
	 * escape in V2 exposes only rows carrying that exact uid.
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
