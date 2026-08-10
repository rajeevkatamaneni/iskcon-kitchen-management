package org.iskcon.kms.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.iskcon.kms.calendar.engine.CalendarDayResult;
import org.iskcon.kms.calendar.engine.VaishnavaCalendarEngine;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Vaishnava calendar as stored and read (E4-S1). Astronomy is computed nightly by
 * {@link VaishnavaCalendarEngine} at the tenant's own location and written to {@code calendar_days};
 * the planner and every other consumer read those rows and never trigger computation on a request.
 *
 * <p>Precompute is idempotent — a re-run upserts each day rather than duplicating it — because the
 * nightly job may run more than once (KmsJob's contract), and because a fresh tenant is precomputed
 * at provisioning and then again on the next nightly sweep.
 */
@Service
public class CalendarService {

	/** 18 rolling months, in days — the horizon the planner and sufficiency feed look across. */
	private static final int HORIZON_DAYS = 550;

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final VaishnavaCalendarEngine engine;

	public CalendarService(JdbcTemplate jdbc, ObjectMapper objectMapper, VaishnavaCalendarEngine engine) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.engine = engine;
	}

	// ---- Read (request path) --------------------------------------------

	/** The calendar between two dates inclusive, for the current tenant, admin overrides applied. */
	@Transactional(readOnly = true)
	public List<CalendarDayView> range(LocalDate from, LocalDate to) {
		return jdbc.query(READ_SELECT + " WHERE cd.cal_date BETWEEN ? AND ? ORDER BY cd.cal_date",
				viewMapper(), from, to);
	}

	/** One day with any override applied, or empty if it hasn't been precomputed yet. */
	@Transactional(readOnly = true)
	public Optional<CalendarDayView> day(LocalDate date) {
		return jdbc.query(READ_SELECT + " WHERE cd.cal_date = ?", viewMapper(), date)
				.stream().findFirst();
	}

	// The computed day joined to any admin override that shadows it (E4-S3). RLS scopes both tables
	// to the current tenant, so a match on the date is a match within the temple.
	private static final String READ_SELECT = """
			SELECT cd.cal_date, cd.tithi, cd.paksa, cd.masa, cd.gaurabda_year, cd.naksatra,
				   cd.is_ekadashi, cd.ekadashi_name, cd.mahadvadashi, cd.fast_type, cd.sunrise,
				   cd.sunset, cd.festivals,
				   o.is_ekadashi AS ov_ekadashi, o.ekadashi_name AS ov_ekadashi_name,
				   o.tithi AS ov_tithi, o.festival_note AS ov_festival_note, o.reason AS ov_reason
			FROM calendar_days cd
			LEFT JOIN calendar_overrides o ON o.cal_date = cd.cal_date
			""";

	// ---- Precompute (nightly job / provisioning) ------------------------

	/**
	 * Computes and upserts the calendar for the current tenant across the horizon, and records the
	 * watermark for the ops page. Assumes a tenant context is set (the sweep and provisioning both
	 * establish one). Returns the number of days written.
	 */
	@Transactional
	public int precomputeForCurrentTenant() {
		TenantLocation loc = currentTenantLocation();
		LocalDate start = LocalDate.now(ZoneId.of(loc.timezone())).withDayOfMonth(1);
		return precompute(loc, start, HORIZON_DAYS);
	}

	/** Precompute a bounded window — the nightly path uses the full horizon; tests use a small one. */
	@Transactional
	public int precomputeForCurrentTenant(LocalDate start, int days) {
		return precompute(currentTenantLocation(), start, days);
	}

	private int precompute(TenantLocation loc, LocalDate start, int days) {
		double tzHours = offsetHours(loc.timezone(), start);

		List<CalendarDayResult> results =
				engine.computeRange(loc.latitude(), loc.longitude(), tzHours, start, days);

		upsertDays(results, tzHours);

		LocalDate through = results.isEmpty() ? start : results.get(results.size() - 1).date();
		jdbc.update("""
				INSERT INTO calendar_precompute_state (tenant_id, last_run_at, computed_through, days_computed)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, now(), ?, ?)
				ON CONFLICT (tenant_id) DO UPDATE
				SET last_run_at = now(), computed_through = EXCLUDED.computed_through,
					days_computed = EXCLUDED.days_computed
				""", through, results.size());

		return results.size();
	}

	private void upsertDays(List<CalendarDayResult> results, double tzHours) {
		jdbc.batchUpdate("""
				INSERT INTO calendar_days (
					id, tenant_id, cal_date, tithi, paksa, masa, gaurabda_year, naksatra,
					is_ekadashi, ekadashi_name, mahadvadashi, fast_type, sunrise, sunset, festivals, computed_at)
				VALUES (
					gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
				ON CONFLICT (tenant_id, cal_date) DO UPDATE SET
					tithi = EXCLUDED.tithi, paksa = EXCLUDED.paksa, masa = EXCLUDED.masa,
					gaurabda_year = EXCLUDED.gaurabda_year, naksatra = EXCLUDED.naksatra,
					is_ekadashi = EXCLUDED.is_ekadashi, ekadashi_name = EXCLUDED.ekadashi_name,
					mahadvadashi = EXCLUDED.mahadvadashi, fast_type = EXCLUDED.fast_type,
					sunrise = EXCLUDED.sunrise, sunset = EXCLUDED.sunset,
					festivals = EXCLUDED.festivals, computed_at = now()
				""",
				results, results.size(),
				(PreparedStatement ps, CalendarDayResult r) -> bindDay(ps, r, tzHours));
	}

	private void bindDay(PreparedStatement ps, CalendarDayResult r, double tzHours) throws SQLException {
		int paksa = r.tithi() >= 15 ? 1 : 0;
		boolean isEkadashi = r.fastCode() == CalendarCodes.FAST_EKADASI;
		ps.setObject(1, r.date());
		ps.setInt(2, r.tithi());
		ps.setInt(3, paksa);
		ps.setInt(4, r.masa());
		setNullableInt(ps, 5, r.gaurabdaYear());
		setNullableInt(ps, 6, r.naksatra());
		ps.setBoolean(7, isEkadashi);
		ps.setString(8, blankToNull(r.ekadashiName()));
		ps.setString(9, CalendarCodes.mahadvadashiName(r.mahadvadashiCode()));
		ps.setString(10, CalendarCodes.fastTypeName(r.fastCode()));
		ps.setObject(11, degToLocalTime(r.sunriseDeg(), tzHours));
		ps.setObject(12, degToLocalTime(r.sunsetDeg(), tzHours));
		ps.setString(13, festivalsJson(r));
	}

	// ---------------------------------------------------------------------

	private String festivalsJson(CalendarDayResult r) {
		List<Map<String, Object>> list = new ArrayList<>();
		if (r.festivals() != null) {
			r.festivals().forEach(f -> list.add(Map.of("text", f.text(), "priority", f.priority())));
		}
		try {
			return objectMapper.writeValueAsString(list);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private List<CalendarDayView.CalendarFestivalView> parseFestivals(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			List<Map<String, Object>> raw = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
			});
			List<CalendarDayView.CalendarFestivalView> out = new ArrayList<>();
			for (Map<String, Object> m : raw) {
				Object p = m.get("priority");
				out.add(new CalendarDayView.CalendarFestivalView(
						String.valueOf(m.get("text")), p instanceof Number n ? n.intValue() : 0));
			}
			return out;
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private TenantLocation currentTenantLocation() {
		return jdbc.query("""
				SELECT latitude, longitude, timezone FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> new TenantLocation(
				rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("timezone")))
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of()));
	}

	/**
	 * The tenant's UTC offset in hours at the given date. GCAL treats the offset as fixed across a
	 * calendar (DST is a display concern it handles separately), so we resolve it once, at the start
	 * date — which also keeps a non-DST-at-runtime zone (e.g. a winter range) correct.
	 */
	private double offsetHours(String timezone, LocalDate onDate) {
		return ZoneId.of(timezone).getRules()
				.getOffset(onDate.atStartOfDay()).getTotalSeconds() / 3600.0;
	}

	/** A 0..360° sun time (0°=00:00) plus the tenant offset, as a local wall-clock time. */
	private LocalTime degToLocalTime(double deg, double tzHours) {
		double local = mod360(deg + tzHours * 15.0);
		double hours = local / 360.0 * 24.0;
		int h = (int) Math.floor(hours);
		int m = (int) Math.floor((hours - h) * 60.0);
		int s = (int) Math.floor(((hours - h) * 60.0 - m) * 60.0);
		return LocalTime.of(h % 24, m, Math.min(s, 59));
	}

	private static double mod360(double d) {
		double x = d % 360.0;
		return x < 0 ? x + 360.0 : x;
	}

	private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
		if (value == null) {
			ps.setNull(idx, Types.INTEGER);
		} else {
			ps.setInt(idx, value);
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private RowMapper<CalendarDayView> viewMapper() {
		return (rs, n) -> {
			int tithi = rs.getInt("tithi");
			int paksa = rs.getInt("paksa");
			boolean isEkadashi = rs.getBoolean("is_ekadashi");
			String ekadashiName = rs.getString("ekadashi_name");
			List<CalendarDayView.CalendarFestivalView> festivals = parseFestivals(rs.getString("festivals"));

			String overrideReason = rs.getString("ov_reason");
			boolean overridden = overrideReason != null;
			if (overridden) {
				Integer ovTithi = (Integer) rs.getObject("ov_tithi");
				if (ovTithi != null) {
					tithi = ovTithi;
					paksa = tithi >= 15 ? 1 : 0;
				}
				isEkadashi = rs.getBoolean("ov_ekadashi");
				ekadashiName = rs.getString("ov_ekadashi_name");
				String note = rs.getString("ov_festival_note");
				if (note != null && !note.isBlank()) {
					festivals = new ArrayList<>(festivals);
					festivals.add(new CalendarDayView.CalendarFestivalView(note.trim(), 0));
				}
			}

			return new CalendarDayView(
					rs.getObject("cal_date", LocalDate.class),
					tithi, paksa,
					rs.getInt("masa"),
					(Integer) rs.getObject("gaurabda_year"),
					(Integer) rs.getObject("naksatra"),
					isEkadashi,
					ekadashiName,
					rs.getString("mahadvadashi"),
					rs.getString("fast_type"),
					rs.getObject("sunrise", LocalTime.class),
					rs.getObject("sunset", LocalTime.class),
					festivals,
					overridden,
					overrideReason);
		};
	}

	private record TenantLocation(double latitude, double longitude, String timezone) {
	}
}
