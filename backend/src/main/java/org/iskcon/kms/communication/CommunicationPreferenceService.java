package org.iskcon.kms.communication;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each devotee has chosen to hear, and the one question the notification service asks of it
 * (E8-S1): may this person be sent this kind of message?
 *
 * <p>The table records only refusals. Being subscribed is the default, so a row per devotee per
 * category would be a table full of people who wanted everything — and the day a category is added,
 * every one of those rows would have to be written again.
 */
@Service
public class CommunicationPreferenceService {

	private final JdbcTemplate jdbc;

	public CommunicationPreferenceService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public CommunicationPreferences forUser(UUID userId) {
		Boolean optedOutOfAll = jdbc.query(
				"SELECT optional_communications_opt_out_at IS NOT NULL AS all_off FROM users WHERE id = ?",
				(rs, n) -> rs.getBoolean("all_off"), userId).stream().findFirst().orElse(false);

		List<String> declined = jdbc.queryForList(
				"SELECT category FROM communication_preferences WHERE user_id = ?", String.class, userId);

		Set<CommunicationCategory> categories = EnumSet.noneOf(CommunicationCategory.class);
		for (String name : declined) {
			CommunicationCategory category = CommunicationCategory.parseOrNull(name);
			// A row naming a category that no longer exists is history, not an error: it was a real
			// refusal of something we have since stopped sending.
			if (category != null) {
				categories.add(category);
			}
		}
		return new CommunicationPreferences(optedOutOfAll, categories);
	}

	/**
	 * The gate, asked once per recipient per send.
	 *
	 * <p>Operational messages skip it entirely — not as an optimisation but as the rule: nothing a
	 * devotee can turn off is allowed to silence a shift reminder.
	 */
	@Transactional(readOnly = true)
	public boolean accepts(UUID userId, CommunicationCategory category) {
		if (category == null || !category.isOptional()) {
			return true;
		}
		return forUser(userId).accepts(category);
	}

	/** Sets one category. Opting back in deletes the refusal rather than recording a second fact. */
	@Transactional
	public void setCategory(UUID userId, CommunicationCategory category, boolean wanted, Source source) {
		if (!category.isOptional()) {
			// Nothing to store: there is no state in which a devotee does not get their shift reminder.
			return;
		}
		if (wanted) {
			jdbc.update("DELETE FROM communication_preferences WHERE user_id = ? AND category = ?",
					userId, category.name());
			return;
		}
		jdbc.update("""
				INSERT INTO communication_preferences (id, tenant_id, user_id, category, source)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				ON CONFLICT (tenant_id, user_id, category) DO NOTHING
				""", userId, category.name(), source.name());
	}

	/**
	 * The blanket switch. Turning it off leaves the individual refusals exactly as they were — a
	 * devotee who declined the newsletter, then everything, then changed their mind about everything,
	 * still does not want the newsletter. Guessing otherwise would put them back on a list they left.
	 */
	@Transactional
	public void setAllOptional(UUID userId, boolean wanted) {
		jdbc.update("""
				UPDATE users SET optional_communications_opt_out_at = ?, updated_at = now() WHERE id = ?
				""", wanted ? null : java.time.OffsetDateTime.now(), userId);
	}

	/** Where a decision was made. An unsubscribe link is a choice taken without signing in. */
	public enum Source {
		PROFILE, UNSUBSCRIBE_LINK
	}
}
