package org.iskcon.kms.shift;

import java.util.Map;
import java.util.regex.Pattern;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant configuration (E6-S7): the daily cap on shift broadcasts, the language the temple
 * works in, and the colours it works in. A tenant with no row yet uses the default, so a setting
 * reads correctly before it has ever been changed.
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

	/**
	 * The shape of a theme's identifier, and only the shape.
	 *
	 * <p>Whether a given theme exists is not a question this side can answer, and deliberately so:
	 * knowing would mean holding a second copy of the catalogue in step with the one in the
	 * frontend. What this refuses is a value that could not be a theme at all, which is the part
	 * that is worth refusing at the boundary rather than storing and puzzling over later.
	 */
	private static final Pattern THEME_ID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

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

	/**
	 * Which colour scheme this temple works in, or null if it has never chosen.
	 *
	 * <p>Opaque here. The themes themselves live in {@code frontend/lib/themes.ts}, beside the
	 * interface they colour, and this application has no opinion about which of them exist — an
	 * identifier that no longer matches one is resolved to the default on the other side rather
	 * than refused on this one. See V72 for why they are not a table.
	 *
	 * <p>Null for a platform operator too, and without a special case: they carry no
	 * {@code app.tenant_id}, so the policy on this table matches nothing.
	 */
	@Transactional(readOnly = true)
	public String themeId() {
		return jdbc.query("SELECT selected_theme_id FROM tenant_settings",
				(rs, n) -> rs.getString("selected_theme_id")).stream().findFirst().orElse(null);
	}

	/**
	 * Records the temple's choice, for everybody who serves there.
	 *
	 * <p>Deliberately not audited, which is worth saying because a change everybody in the temple
	 * can see looks like something that should be. Nothing else on the settings screen is audited
	 * either — not the language, not the broadcast cap, not the payment provider — and auditing one
	 * preference while three sit unaudited beside it would read as a decision about this one rather
	 * than the accident it would be. If settings become auditable, they become auditable together.
	 */
	@Transactional
	public void setThemeId(String themeId) {
		if (themeId == null || !THEME_ID.matcher(themeId).matches()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "themeId", "reason", "a theme is named in lower case with hyphens"));
		}
		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, selected_theme_id)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?)
				ON CONFLICT (tenant_id)
				DO UPDATE SET selected_theme_id = EXCLUDED.selected_theme_id, updated_at = now()
				""", themeId);
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
