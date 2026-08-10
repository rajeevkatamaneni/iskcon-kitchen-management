package org.iskcon.kms.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's own account: what channel to reach them on, and their communication consent.
 *
 * <p>Every operation acts on the caller's own row — the id comes from the authenticated principal,
 * never from the request — so there is no way to read or change anyone else's profile, and RLS
 * scopes the row to the caller's tenant on top of that.
 */
@Service
public class ProfileService {

	private static final String SELECT_PROFILE = """
			SELECT full_name, email, phone, preferred_channel, contact_consent_at, consent_version, role
			FROM users WHERE id = ?
			""";

	private final JdbcTemplate jdbc;

	public ProfileService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public ProfileView currentProfile(AuthenticatedUser actor) {
		return jdbc.queryForObject(SELECT_PROFILE, (rs, rowNum) -> toView(rs), actor.getUserId());
	}

	/** Changes the preferred channel and returns the updated profile. */
	@Transactional
	public ProfileView changeChannel(AuthenticatedUser actor, String channel) {
		User.NotificationChannel parsed = parseChannel(channel);
		jdbc.update(
				"UPDATE users SET preferred_channel = ?, updated_at = now() WHERE id = ?",
				parsed.name(), actor.getUserId());
		return currentProfile(actor);
	}

	/**
	 * Records the caller's consent to be contacted, stamping the moment and the wording accepted.
	 * Re-consenting simply moves both forward — that is how a person re-agrees after the text has
	 * changed.
	 */
	@Transactional
	public ProfileView giveConsent(AuthenticatedUser actor) {
		jdbc.update(
				"UPDATE users SET contact_consent_at = now(), consent_version = ?, updated_at = now() "
						+ "WHERE id = ?",
				CommunicationConsent.CURRENT_VERSION, actor.getUserId());
		return currentProfile(actor);
	}

	private ProfileView toView(ResultSet rs) throws SQLException {
		OffsetDateTime consentAt = rs.getObject("contact_consent_at", OffsetDateTime.class);
		String consentVersion = rs.getString("consent_version");

		// Needed when never given, or given against wording we have since revised.
		boolean consentNeeded =
				consentAt == null || !CommunicationConsent.CURRENT_VERSION.equals(consentVersion);

		return new ProfileView(
				rs.getString("full_name"),
				rs.getString("email"),
				rs.getString("phone"),
				rs.getString("preferred_channel"),
				consentAt == null ? null : consentAt.toInstant(),
				consentVersion,
				consentNeeded,
				CommunicationConsent.CURRENT_VERSION,
				CommunicationConsent.TEXT,
				rs.getString("role"));
	}

	private User.NotificationChannel parseChannel(String channel) {
		try {
			return User.NotificationChannel.valueOf(channel);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED,
					Map.of("field", "preferredChannel", "value", channel),
					e);
		}
	}
}
