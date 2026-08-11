package org.iskcon.kms.shift;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant configuration (E6-S7) — currently just the daily cap on shift broadcasts. A tenant with
 * no row yet uses the default, so the setting reads correctly before it has ever been changed.
 */
@Service
public class TenantSettingsService {

	static final int DEFAULT_BROADCAST_DAILY_LIMIT = 3;

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
