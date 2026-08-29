package org.iskcon.kms.theme;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The colour a temple wears.
 *
 * <p>Two halves that are easy to confuse. The <em>catalogue</em> belongs to the platform: sixteen
 * packs in one global table, the same for everybody, maintained by an operator. The
 * <em>choice</em> belongs to the temple: one column on {@code tenant_settings}, changed by its
 * administrator, and applying to every person who serves there — a kitchen where two people see
 * two different colours is a kitchen where they cannot describe a screen to each other.
 *
 * <p>Writing to the catalogue is a platform operator's act and is refused by the RLS policies in
 * V72, which name {@code SUPER_ADMIN} in the database itself. There is no method here that writes
 * a pack, which is the strongest statement this class can make about it.
 *
 * <p>{@link #selectedForCurrentTenant()} never returns null. A temple that has never chosen, and a
 * platform operator who has no temple at all, both get the default pack — so the client always
 * holds a complete palette and never has to carry a second, drifting copy of one.
 */
@Service
public class ThemeService {

	/**
	 * The pack seeded by V72: the terracotta the application was designed in. What a temple wears
	 * before it chooses, and what it can come back to by name afterwards.
	 */
	static final String DEFAULT_SLUG = "temple-terracotta";

	private static final String SELECT_COLUMNS =
			"SELECT id, slug, name, family, description, palette FROM theme_packs";

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public ThemeService(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	/**
	 * Every pack, grouped the way the screen shows them: the three families in order of loudness,
	 * and within a family the order they were designed in.
	 *
	 * <p>Ordered here rather than in the caller because the order is a design decision — bright
	 * first, quiet last, and alphabetical within a family would scatter packs that were built to be
	 * seen beside each other.
	 */
	@Transactional(readOnly = true)
	public List<ThemePackView> catalogue() {
		return jdbc.query(SELECT_COLUMNS + """
				 ORDER BY CASE family
					WHEN 'VIBRANT' THEN 1
					WHEN 'BALANCED' THEN 2
					ELSE 3
				 END, sort_order, name
				""", rowMapper());
	}

	/**
	 * The pack this request's temple is wearing, falling back to the default.
	 *
	 * <p>Two queries rather than one outer join. The fallback is not a row the first query failed
	 * to find — it is a different question being asked because the first had no answer, and an
	 * outer join that silently returns the default row would hide the difference between "no
	 * temple", "no setting" and "the chosen pack was withdrawn".
	 */
	@Transactional(readOnly = true)
	public ThemePackView selectedForCurrentTenant() {
		List<ThemePackView> chosen = jdbc.query(SELECT_COLUMNS + """
				 WHERE id = (
					SELECT selected_theme_pack_id FROM tenant_settings
					WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				 )
				""", rowMapper());
		return chosen.stream().findFirst().orElseGet(this::defaultPack);
	}

	/** The slug this temple has actually chosen, or null when it never has. */
	@Transactional(readOnly = true)
	public String selectedSlug() {
		return jdbc.query("""
				SELECT p.slug FROM theme_packs p
				JOIN tenant_settings s ON s.selected_theme_pack_id = p.id
				WHERE s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("slug")).stream().findFirst().orElse(null);
	}

	/**
	 * Records this temple's choice, for everybody who serves there.
	 *
	 * <p>Deliberately not audited, which is worth saying because a change everybody in the temple
	 * can see looks like something that should be. Nothing else on the settings screen is audited
	 * either — not the language, not the broadcast cap, not the payment provider — and auditing one
	 * preference while three sit unaudited beside it would read as a decision about this one rather
	 * than the accident it would be. If settings become auditable, they become auditable together.
	 */
	@Transactional
	public void select(String slug) {
		UUID packId = jdbc.query("SELECT id FROM theme_packs WHERE slug = ?",
						(rs, n) -> rs.getObject("id", UUID.class), slug)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.THEME_PACK_NOT_FOUND,
						Map.of("slug", String.valueOf(slug))));

		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, selected_theme_pack_id)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?)
				ON CONFLICT (tenant_id)
				DO UPDATE SET selected_theme_pack_id = EXCLUDED.selected_theme_pack_id,
					updated_at = now()
				""", packId);
	}

	private ThemePackView defaultPack() {
		return jdbc.query(SELECT_COLUMNS + " WHERE slug = ?", rowMapper(), DEFAULT_SLUG)
				.stream().findFirst()
				// V72 seeds this row and nothing may delete it while a temple points at it. If it
				// is gone the catalogue is broken in a way no colour choice can express, and a
				// half-painted screen would be the only symptom.
				.orElseThrow(() -> new IllegalStateException(
						"The default theme pack " + DEFAULT_SLUG + " is missing from the catalogue"));
	}

	private RowMapper<ThemePackView> rowMapper() {
		return (rs, n) -> new ThemePackView(
				rs.getObject("id", UUID.class),
				rs.getString("slug"),
				rs.getString("name"),
				rs.getString("family"),
				rs.getString("description"),
				palette(rs));
	}

	private Map<String, String> palette(ResultSet rs) throws SQLException {
		Object stored = rs.getObject("palette");
		try {
			return mapper.readValue(String.valueOf(stored), new TypeReference<Map<String, String>>() {
			});
		}
		catch (Exception e) {
			throw new IllegalStateException("A theme pack's stored palette could not be read", e);
		}
	}
}
