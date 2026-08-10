package org.iskcon.kms.occasion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The festival occasion catalog (E4-S2). A COMPUTED occasion carries a substring matched against the
 * calendar engine's festival texts, so it resolves to whatever dates the astronomy produces; a
 * MANUAL occasion recurs on a fixed month/day. Occasions are seeded on provisioning with the major
 * pan-ISKCON festivals and extended by the temple (a temple anniversary is inherently local).
 */
@Service
public class OccasionService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public OccasionService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	// ---- Read -----------------------------------------------------------

	@Transactional(readOnly = true)
	public List<OccasionView> list() {
		return jdbc.query(SELECT + " ORDER BY name", MAPPER);
	}

	/**
	 * Every occasion occurrence between two dates inclusive: COMPUTED ones matched against the
	 * precomputed calendar, MANUAL ones by their recurring date. This is what the planner overlays
	 * and what determines a day's festival day-type (E4-S4).
	 */
	@Transactional(readOnly = true)
	public List<ResolvedOccasion> resolve(LocalDate from, LocalDate to) {
		List<ResolvedOccasion> out = new ArrayList<>();

		for (OccasionView o : list()) {
			if (o.type() == OccasionType.COMPUTED) {
				List<LocalDate> dates = jdbc.query("""
						SELECT cal_date FROM calendar_days
						WHERE cal_date BETWEEN ? AND ?
						  AND lower(festivals::text) LIKE ? ESCAPE '\\'
						ORDER BY cal_date
						""", (rs, n) -> rs.getObject("cal_date", LocalDate.class),
						from, to, "%" + escapeLike(o.matchText().toLowerCase()) + "%");
				for (LocalDate d : dates) {
					out.add(new ResolvedOccasion(o.id(), o.name(), d, o.defaultServings(), o.type()));
				}
			} else {
				for (int year = from.getYear(); year <= to.getYear(); year++) {
					LocalDate d = safeDate(year, o.fixedMonth(), o.fixedDay());
					if (d != null && !d.isBefore(from) && !d.isAfter(to)) {
						out.add(new ResolvedOccasion(o.id(), o.name(), d, o.defaultServings(), o.type()));
					}
				}
			}
		}

		out.sort(Comparator.comparing(ResolvedOccasion::date).thenComparing(ResolvedOccasion::name));
		return out;
	}

	// ---- Write ----------------------------------------------------------

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateOccasionRequest request) {
		validate(request.type(), request.matchText(), request.fixedMonth(), request.fixedDay());
		UUID id = UUID.randomUUID();
		try {
			jdbc.update(connection -> {
				var ps = connection.prepareStatement("""
						INSERT INTO occasions (
							id, tenant_id, name, type, match_text, fixed_month, fixed_day,
							default_servings, notes, seeded)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?, false)
						""");
				ps.setObject(1, id);
				ps.setString(2, request.name().trim());
				ps.setString(3, request.type().name());
				ps.setString(4, request.type() == OccasionType.COMPUTED ? request.matchText().trim() : null);
				setNullableInt(ps, 5, request.type() == OccasionType.MANUAL ? request.fixedMonth() : null);
				setNullableInt(ps, 6, request.type() == OccasionType.MANUAL ? request.fixedDay() : null);
				setNullableInt(ps, 7, request.defaultServings());
				ps.setString(8, trimToNull(request.notes()));
				return ps;
			});
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.OCCASION_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.OCCASION_ADDED, AuditEntityType.OCCASION, id,
				null, snapshot(request.name().trim(), request.type()), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateOccasionRequest request) {
		OccasionView before = findById(id).orElseThrow(() -> notFound(id));
		validate(before.type(), request.matchText(), request.fixedMonth(), request.fixedDay());
		try {
			jdbc.update("""
					UPDATE occasions
					SET name = ?, match_text = ?, fixed_month = ?, fixed_day = ?,
						default_servings = ?, notes = ?, updated_at = now()
					WHERE id = ?
					""",
					request.name().trim(),
					before.type() == OccasionType.COMPUTED ? request.matchText().trim() : null,
					before.type() == OccasionType.MANUAL ? request.fixedMonth() : null,
					before.type() == OccasionType.MANUAL ? request.fixedDay() : null,
					request.defaultServings(), trimToNull(request.notes()), id);
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.OCCASION_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}

		auditService.record(actor, AuditAction.OCCASION_UPDATED, AuditEntityType.OCCASION, id,
				snapshot(before.name(), before.type()), snapshot(request.name().trim(), before.type()), null);
	}

	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		OccasionView existing = findById(id).orElseThrow(() -> notFound(id));
		// No hard reference from meal plans (they keep the occasion name as text, E4-S4), so removing
		// an occasion never orphans historical plans.
		jdbc.update("DELETE FROM occasions WHERE id = ?", id);
		auditService.record(actor, AuditAction.OCCASION_REMOVED, AuditEntityType.OCCASION, id,
				snapshot(existing.name(), existing.type()), null, null);
	}

	// ---- Seed (provisioning) --------------------------------------------

	/**
	 * Seeds the major pan-ISKCON festivals for the current tenant. Idempotent — a name already
	 * present is left as the temple has it. Temple-specific occasions (anniversary, guru appearance
	 * days) are left for the temple to add.
	 */
	@Transactional
	public void seedForCurrentTenant() {
		for (Seed s : SEEDS) {
			jdbc.update("""
					INSERT INTO occasions (tenant_id, name, type, match_text, default_servings, seeded)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, 'COMPUTED', ?, ?, true)
					ON CONFLICT (tenant_id, lower(name)) DO NOTHING
					""", s.name(), s.matchText(), s.servings());
		}
	}

	// ---------------------------------------------------------------------

	private void validate(OccasionType type, String matchText, Integer fixedMonth, Integer fixedDay) {
		if (type == OccasionType.COMPUTED && (matchText == null || matchText.isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "matchText"));
		}
		if (type == OccasionType.MANUAL && (fixedMonth == null || fixedDay == null)) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "fixedDate"));
		}
	}

	private Optional<OccasionView> findById(UUID id) {
		return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
	}

	private LocalDate safeDate(int year, int month, int day) {
		try {
			return LocalDate.of(year, month, day);
		} catch (RuntimeException e) {
			return null; // e.g. 29 Feb in a non-leap year — simply doesn't occur that year
		}
	}

	private Map<String, Object> snapshot(String name, OccasionType type) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("name", name);
		s.put("type", type.name());
		return s;
	}

	private static void setNullableInt(java.sql.PreparedStatement ps, int idx, Integer v) throws java.sql.SQLException {
		if (v == null) {
			ps.setNull(idx, java.sql.Types.INTEGER);
		} else {
			ps.setInt(idx, v);
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static String escapeLike(String v) {
		return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("occasionId", id));
	}

	private static final String SELECT = """
			SELECT id, name, type, match_text, fixed_month, fixed_day, default_servings, notes, seeded
			FROM occasions
			""";

	private static final RowMapper<OccasionView> MAPPER = (rs, n) -> new OccasionView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			OccasionType.valueOf(rs.getString("type")),
			rs.getString("match_text"),
			(Integer) rs.getObject("fixed_month"),
			(Integer) rs.getObject("fixed_day"),
			(Integer) rs.getObject("default_servings"),
			rs.getString("notes"),
			rs.getBoolean("seeded"));

	private record Seed(String name, String matchText, int servings) {
	}

	// Match texts are substrings verified to appear in the engine's festival output for Bengaluru.
	private static final List<Seed> SEEDS = List.of(
			new Seed("Nityananda Trayodasi", "Nityananda Trayodasi", 300),
			new Seed("Gaura Purnima", "Gaura Purnima", 1000),
			new Seed("Rama Navami", "Rama Navami", 500),
			new Seed("Nrsimha Caturdasi", "Nrsimha Caturdasi", 500),
			new Seed("Snana Yatra", "Snana Yatra", 300),
			new Seed("Ratha Yatra", "Ratha Yatra", 1000),
			new Seed("Guru Purnima", "Guru (Vyasa) Purnima", 300),
			new Seed("Balarama Purnima", "Balarama -- Appearance", 500),
			new Seed("Sri Krsna Janmastami", "Janmastami", 2000),
			new Seed("Srila Prabhupada Appearance", "Srila Prabhupada -- Appearance", 800),
			new Seed("Radhastami", "Radhastami", 800),
			new Seed("Govardhana Puja", "Govardhana Puja", 500),
			new Seed("Srila Prabhupada Disappearance", "Srila Prabhupada -- Disappearance", 500));
}
