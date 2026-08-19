package org.iskcon.kms.tenant;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.geo.GeocodingProvider;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joining a temple (E1-S17).
 *
 * <p>Everywhere else in the product, the tenant comes from a verified record and never from the
 * request — it is the rule the whole isolation design rests on. This is the single exception, and
 * only because the request <em>is</em> the person choosing where they serve. It is narrowed until
 * the exception carries no weight: the only row it can write is the caller's own, only as a
 * volunteer, only at a temple that exists and is open, and only where they are not already a member.
 * It reads nothing else in that temple's context.
 */
@Service
public class MembershipService {

	private final JdbcTemplate jdbc;
	private final MembershipWriter writer;
	private final GeocodingProvider geocoding;

	public MembershipService(JdbcTemplate jdbc, MembershipWriter writer, GeocodingProvider geocoding) {
		this.jdbc = jdbc;
		this.writer = writer;
		this.geocoding = geocoding;
	}

	/**
	 * The temples a devotee might join, narrowed the way they would narrow them: by where they are
	 * standing, or by the name of a place. A flat list of every temple on the platform is not an
	 * answer once there are hundreds of them, so it is the last resort rather than the default.
	 *
	 * <p>tenants is the registry itself and carries no tenant_id, so it is not RLS-scoped. What comes
	 * back is a name and a place — nothing about how a temple runs.
	 */
	public List<MembershipController.TempleSummary> templesToJoin(String near, String q, double withinKm) {
		double[] here = coordinates(near);

		if (here != null) {
			return withinRadius(here[0], here[1], withinKm);
		}

		return byName(q, withinKm);
	}

	/**
	 * Temples within a radius of a point, nearest first. Great-circle distance on the temple's own
	 * coordinates, which every temple has because the Vaishnava calendar is computed from them (V1).
	 */
	private List<MembershipController.TempleSummary> withinRadius(double lat, double lng, double withinKm) {
		return jdbc.query("""
					SELECT id, name, address, distance_km FROM (
						SELECT id, name, address,
							6371 * acos(least(1, greatest(-1,
								cos(radians(?)) * cos(radians(latitude))
									* cos(radians(longitude) - radians(?))
								+ sin(radians(?)) * sin(radians(latitude))))) AS distance_km
						FROM tenants
					) near_by
					WHERE distance_km <= ?
					ORDER BY distance_km
					""", MAPPER, lat, lng, lat, Math.max(1, withinKm));
	}

	/** What they typed, matched against temple names and addresses — the last resort. */
	private List<MembershipController.TempleSummary> byName(String q, double withinKm) {
		if (q != null && !q.isBlank()) {
			// A place first: "Jayanagar" is a neighbourhood, not a temple's name, and what the
			// person means by it is "temples near there". Falls through to a name match when no map
			// service is configured, when it cannot find the place, or when nothing is near it.
			Optional<GeocodingProvider.Coordinates> place = geocoding.locate(q);
			if (place.isPresent()) {
				List<MembershipController.TempleSummary> nearThere = withinRadius(
						place.get().latitude(), place.get().longitude(), withinKm);
				if (!nearThere.isEmpty()) {
					return nearThere;
				}
			}

			String like = "%" + q.trim() + "%";
			return jdbc.query("""
					SELECT id, name, address, NULL::float8 AS distance_km FROM tenants
					WHERE name ILIKE ? OR address ILIKE ?
					ORDER BY name
					LIMIT 50
					""", MAPPER, like, like);
		}

		return jdbc.query("""
				SELECT id, name, address, NULL::float8 AS distance_km FROM tenants
				ORDER BY name
				LIMIT 50
				""", MAPPER);
	}

	/** "12.9716,77.5946" as the browser reports it, or null if it is missing or unreadable. */
	private static double[] coordinates(String near) {
		if (near == null || !near.contains(",")) {
			return null;
		}
		try {
			String[] parts = near.split(",", 2);
			double lat = Double.parseDouble(parts[0].trim());
			double lng = Double.parseDouble(parts[1].trim());
			if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
				return null;
			}
			return new double[] {lat, lng};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static final org.springframework.jdbc.core.RowMapper<MembershipController.TempleSummary>
			MAPPER = (rs, n) -> new MembershipController.TempleSummary(
					rs.getObject("id", UUID.class),
					rs.getString("name"),
					rs.getString("address"),
					rs.getObject("distance_km") == null
							? null : Math.round(rs.getDouble("distance_km") * 10) / 10.0);

	public UUID join(AuthenticatedUser actor, UUID templeId, JoinTempleRequest request) {
		String uid = actor.getFirebaseUid();
		if (uid == null || uid.isBlank()) {
			throw new ApplicationException(ErrorCode.NOT_AUTHENTICATED, Map.of());
		}
		requireTemple(templeId);

		// From here the request speaks for the chosen temple, which is what makes the insert legal
		// under the write policy. Set before the transaction opens, because the tenant is stamped on
		// the connection at checkout — a context change inside a transaction never reaches the
		// session. Restored at the end, so a person joining a second temple does not leave this
		// request pointed at it.
		UUID previous = TenantContext.get().orElse(null);
		TenantContext.set(templeId);
		try {
			return writer.createMembership(actor, uid, request);
		} finally {
			if (previous == null) {
				TenantContext.clear();
			} else {
				TenantContext.set(previous);
			}
		}
	}

	/** The write itself, in its own transaction — opened after the tenant is in place. */
	@Service
	static class MembershipWriter {

		private final JdbcTemplate jdbc;
		private final UserRepository userRepository;
		private final AuditService auditService;

		MembershipWriter(JdbcTemplate jdbc, UserRepository userRepository, AuditService auditService) {
			this.jdbc = jdbc;
			this.userRepository = userRepository;
			this.auditService = auditService;
		}

		@Transactional
		UUID createMembership(AuthenticatedUser actor, String uid, JoinTempleRequest request) {
			Optional<UUID> already = existingMembership(uid);
			if (already.isPresent()) {
				return already.get();
			}

			UUID id = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO users (id, tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
							?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE')
					""", id, uid, request.fullName(),
					lower(request.email() != null && !request.email().isBlank()
							? request.email() : actor.getEmail()),
					request.phone());

			// Recorded as the person's own act, because it is: nobody invited them.
			auditService.record(
					userRepository.findById(id).map(AuthenticatedUser::new).orElse(actor),
					AuditAction.USER_ADDED, AuditEntityType.USER, id,
					null,
					Map.of("role", "VOLUNTEER", "joined", "self"),
					"Joined this temple as a volunteer.");

			return id;
		}

		/** Their membership here, if they already have one — joining twice is a no-op, not an error. */
		private Optional<UUID> existingMembership(String uid) {
			return jdbc.query("""
					SELECT id FROM users
					WHERE firebase_uid = ?
					  AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", (rs, n) -> rs.getObject("id", UUID.class), uid).stream().findFirst();
		}
	}

	// ---------------------------------------------------------------------

	private void requireTemple(UUID templeId) {
		Integer found = jdbc.queryForObject(
				"SELECT count(*) FROM tenants WHERE id = ?", Integer.class, templeId);
		if (found == null || found == 0) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", templeId));
		}
	}

	private static String lower(String value) {
		return value == null ? null : value.toLowerCase();
	}
}
