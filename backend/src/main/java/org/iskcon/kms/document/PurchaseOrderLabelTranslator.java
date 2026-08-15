package org.iskcon.kms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.translation.GlossaryService;
import org.iskcon.kms.translation.TranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Translates the PO sheet's fixed labels (E5-S5), the same way the sheet's content is translated:
 * glossary first — so a tenant can pin a term like GSTIN to stay untranslated — then machine
 * translation for the rest. Nothing here is hand-curated per language, so a sheet renders in any of
 * the languages the picker offers.
 *
 * <p>Labels are a small set that changes only when the template ships a new version, so their
 * translation is cached per (tenant, language, label-set version) in Postgres — translated once, not
 * on every render (SYSTEM_DESIGN §6). If translation fails, the English labels are returned and not
 * cached, so a sheet always renders and the next attempt retries.
 */
@Service
public class PurchaseOrderLabelTranslator {

	/** Bump when the English label set changes, to invalidate cached translations. */
	static final int LABEL_SET_VERSION = 1;

	private static final Logger log = LoggerFactory.getLogger(PurchaseOrderLabelTranslator.class);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final GlossaryService glossaryService;
	private final TranslationProvider provider;

	public PurchaseOrderLabelTranslator(
			JdbcTemplate jdbc, ObjectMapper objectMapper, GlossaryService glossaryService,
			TranslationProvider provider) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.glossaryService = glossaryService;
		this.provider = provider;
	}

	/** The sheet labels for a language, in the template's fixed order. English needs no translation. */
	@Transactional
	public List<String> labels(String language) {
		if (isEnglish(language)) {
			return english();
		}
		List<String> cached = readCache(language);
		if (cached != null) {
			return cached;
		}
		try {
			List<String> translated = translate(language);
			writeCache(language, translated);
			return translated;
		} catch (RuntimeException e) {
			// A sheet must always render; fall back to English and let the next request retry.
			log.warn("Label translation to {} failed; falling back to English labels", language, e);
			return english();
		}
	}

	private List<String> translate(String language) {
		Map<String, String> glossary = glossaryService.lookup(language);
		List<String> source = english();
		String[] out = new String[source.size()];
		List<String> mt = new ArrayList<>();
		int[] mtIndex = new int[source.size()];
		for (int i = 0; i < source.size(); i++) {
			String override = glossary.get(source.get(i).toLowerCase());
			if (override != null) {
				out[i] = override;
				mtIndex[i] = -1;
			} else {
				mtIndex[i] = mt.size();
				mt.add(source.get(i));
			}
		}
		if (!mt.isEmpty()) {
			List<String> translated = provider.translate(mt, "en", language);
			for (int i = 0; i < source.size(); i++) {
				if (mtIndex[i] >= 0) {
					out[i] = translated.get(mtIndex[i]);
				}
			}
		}
		return List.of(out);
	}

	private List<String> readCache(String language) {
		// Matched on the provider as well as the label set: labels are only a hit for the engine that
		// produced them, so switching engines re-translates instead of printing the old one's words on
		// a sheet a vendor will read. The write upserts, so the stale row is replaced in place.
		List<String> rows = jdbc.query("""
				SELECT content FROM po_label_translations
				WHERE language = ? AND label_set_version = ? AND provider = ?
				""", (rs, n) -> rs.getString("content"), language, LABEL_SET_VERSION, provider.name());
		if (rows.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.readValue(rows.get(0), new TypeReference<List<String>>() {
			});
		} catch (JsonProcessingException e) {
			return null; // A corrupt cache row is simply re-translated.
		}
	}

	private void writeCache(String language, List<String> labels) {
		String json;
		try {
			json = objectMapper.writeValueAsString(labels);
		} catch (JsonProcessingException e) {
			return; // Failing to cache is not worth failing the render over.
		}
		jdbc.update("""
				INSERT INTO po_label_translations (tenant_id, language, label_set_version, content, provider)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, CAST(? AS jsonb), ?)
				ON CONFLICT (tenant_id, language, label_set_version)
				DO UPDATE SET content = EXCLUDED.content, provider = EXCLUDED.provider, updated_at = now()
				""", language, LABEL_SET_VERSION, json, provider.name());
	}

	private static List<String> english() {
		return PurchaseOrderSheetTemplate.Labels.english().asList();
	}

	private static boolean isEnglish(String language) {
		return language == null || language.isBlank() || "en".equalsIgnoreCase(language);
	}
}
