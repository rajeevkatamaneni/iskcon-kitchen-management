package org.iskcon.kms.user;

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
}
