package org.iskcon.kms.shift;

import java.util.Map;
import java.util.regex.Pattern;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant configuration (E6-S7): the daily cap on shift broadcasts, and the language the temple
 * works in. A tenant with no row yet uses the default, so a setting reads correctly before it has
 * ever been changed.
 *
 * <p>The language lives on {@code tenants.locale} rather than here, because it predates this table
 * and is the only statement of language a temple makes anywhere in the schema. It has been
 * unwritable since V1 — every temple carried the {@code en-IN} the column defaulted to — which was
 * fine while nothing read it and stopped being fine when the job card started printing in "the
 * temple's own language" (build brief §3). Adding a second "kitchen language" beside it was the
 * alternative and was rejected: two places to say the same thing is two places to keep in step.
 */
@Service
public class TenantSettingsService {

	static final int DEFAULT_BROADCAST_DAILY_LIMIT = 3;

	/** What V1 gives every temple, and what a temple that has never chosen still works in. */
	static final String DEFAULT_LOCALE = "en-IN";

	/** An ISO 639-1 code. The region is ours to add — every temple in this release is in India. */
	private static final Pattern LANGUAGE_TAG = Pattern.compile("^[A-Za-z]{2}$");

	private final JdbcTemplate jdbc;

	public TenantSettingsService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public int volunteerBroadcastDailyLimit() {
		Integer limit = jdbc.query("SELECT volunteer_broadcast_daily_limit FROM tenant_settings",
				(rs, n) -> rs.getInt("volunteer_broadcast_daily_limit")).stream().findFirst().orElse(null);
		return limit == null ? DEFAULT_BROADCAST_DAILY_LIMIT : limit;
	}

	/**
	 * The temple's own language as a BCP-47 tag ({@code en-IN}, {@code kn-IN}). Never null: V1 gives
	 * the column a NOT NULL default, and a temple that has never chosen works in English.
	 */
	@Transactional(readOnly = true)
	public String locale() {
		return jdbc.query("""
				SELECT locale FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("locale")).stream().findFirst().orElse(DEFAULT_LOCALE);
	}

	/**
	 * Sets the temple's language. Stored as a region-qualified tag so the column keeps the shape it
	 * has always had; the callers that want a language ask for the subtag before the dash.
	 */
	@Transactional
	public void setLanguage(String language) {
		if (language == null || language.isBlank() || !LANGUAGE_TAG.matcher(language).matches()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "language", "reason", "a language is a two-letter code such as kn or hi"));
		}
		jdbc.update("""
				UPDATE tenants SET locale = ?, updated_at = now()
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", language.toLowerCase() + "-IN");
	}

	@Transactional
	public void setVolunteerBroadcastDailyLimit(int limit) {
		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, volunteer_broadcast_daily_limit)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?)
				ON CONFLICT (tenant_id)
				DO UPDATE SET volunteer_broadcast_daily_limit = EXCLUDED.volunteer_broadcast_daily_limit,
					updated_at = now()
				""", limit);
	}
}
