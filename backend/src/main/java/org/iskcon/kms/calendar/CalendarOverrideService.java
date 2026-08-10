package org.iskcon.kms.calendar;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin calendar overrides (E4-S3). Writing an override shadows the computed day for every consumer
 * (the read join in {@link CalendarService} applies it); it lives in its own table so the nightly
 * recompute never clobbers it, and removing it reverts to computed truth. Both actions are audited
 * with the before/after and the mandatory reason.
 */
@Service
public class CalendarOverrideService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public CalendarOverrideService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional
	public void set(AuthenticatedUser actor, LocalDate date, SetCalendarOverrideRequest request) {
		Map<String, Object> before = computedSnapshot(date);

		UUID id = jdbc.queryForObject("""
				INSERT INTO calendar_overrides (
					id, tenant_id, cal_date, is_ekadashi, ekadashi_name, tithi, festival_note,
					reason, created_by)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (tenant_id, cal_date) DO UPDATE SET
					is_ekadashi = EXCLUDED.is_ekadashi, ekadashi_name = EXCLUDED.ekadashi_name,
					tithi = EXCLUDED.tithi, festival_note = EXCLUDED.festival_note,
					reason = EXCLUDED.reason, created_by = EXCLUDED.created_by, updated_at = now()
				RETURNING id
				""", UUID.class,
				date, request.isEkadashi(), trimToNull(request.ekadashiName()), request.tithi(),
				trimToNull(request.festivalNote()), request.reason().trim(), actor.getUserId());

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("date", date.toString());
		after.put("isEkadashi", request.isEkadashi());
		if (request.tithi() != null) {
			after.put("tithi", request.tithi());
		}
		auditService.record(actor, AuditAction.CALENDAR_OVERRIDDEN, AuditEntityType.CALENDAR_DAY, id,
				before, after, request.reason().trim());
	}

	@Transactional
	public void revert(AuthenticatedUser actor, LocalDate date) {
		UUID id = existingOverrideId(date)
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("date", date)));
		jdbc.update("DELETE FROM calendar_overrides WHERE id = ?", id);
		auditService.record(actor, AuditAction.CALENDAR_OVERRIDE_REVERTED, AuditEntityType.CALENDAR_DAY, id,
				Map.of("date", date.toString()), null, null);
	}

	// ---------------------------------------------------------------------

	private Optional<UUID> existingOverrideId(LocalDate date) {
		return jdbc.query("SELECT id FROM calendar_overrides WHERE cal_date = ?",
				(rs, n) -> rs.getObject("id", UUID.class), date).stream().findFirst();
	}

	private Map<String, Object> computedSnapshot(LocalDate date) {
		return jdbc.query("SELECT is_ekadashi, tithi FROM calendar_days WHERE cal_date = ?",
				(rs, n) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("date", date.toString());
					m.put("isEkadashi", rs.getBoolean("is_ekadashi"));
					m.put("tithi", rs.getInt("tithi"));
					return m;
				}, date).stream().findFirst().orElseGet(() -> Map.of("date", date.toString()));
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}
}
