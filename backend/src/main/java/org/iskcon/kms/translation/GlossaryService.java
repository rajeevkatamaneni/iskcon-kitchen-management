package org.iskcon.kms.translation;

import java.util.HashMap;
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
 * The per-tenant translation glossary (E2-S6): preferred translations for culinary terms, consulted
 * before machine translation. All tenant-scoped by RLS.
 */
@Service
public class GlossaryService {

	private final JdbcTemplate jdbc;

	public GlossaryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<GlossaryEntryView> list(String language) {
		if (language == null || language.isBlank()) {
			return jdbc.query("""
					SELECT id, language, source_term, target_term FROM translation_glossary
					ORDER BY language, source_term
					""", MAPPER);
		}
		return jdbc.query("""
				SELECT id, language, source_term, target_term FROM translation_glossary
				WHERE language = ? ORDER BY source_term
				""", MAPPER, language);
	}

	/** Adds or updates a term (upsert on language + term), returning its id. */
	@Transactional
	public UUID upsert(AddGlossaryEntryRequest request) {
		return jdbc.queryForObject("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				ON CONFLICT (tenant_id, language, lower(source_term))
				DO UPDATE SET target_term = EXCLUDED.target_term
				RETURNING id
				""", UUID.class, request.language(), request.sourceTerm().trim(), request.targetTerm().trim());
	}

	@Transactional
	public void delete(UUID id) {
		int deleted = jdbc.update("DELETE FROM translation_glossary WHERE id = ?", id);
		if (deleted == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("glossaryEntryId", id));
		}
	}

	/** The overrides for a language as a case-insensitive lookup from source term to target term. */
	@Transactional(readOnly = true)
	public Map<String, String> lookup(String language) {
		Map<String, String> map = new HashMap<>();
		jdbc.query("SELECT source_term, target_term FROM translation_glossary WHERE language = ?",
				(rs) -> {
					map.put(rs.getString("source_term").toLowerCase(), rs.getString("target_term"));
				}, language);
		return map;
	}

	private static final RowMapper<GlossaryEntryView> MAPPER = (rs, rowNum) -> new GlossaryEntryView(
			rs.getObject("id", UUID.class),
			rs.getString("language"),
			rs.getString("source_term"),
			rs.getString("target_term"));
}
