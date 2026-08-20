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
 * Translates a printed document's fixed labels, the same way its content is translated (E5-S5):
 * glossary first — so a temple can pin a term it wants left alone — then machine translation for the
 * rest. Nothing is hand-curated per language, so a document renders in any language the picker
 * offers rather than in a chosen few.
 *
 * <p>Labels are a small set that changes only when a template ships a new version, so their
 * translation is cached per (tenant, label set, language, label-set version) and matched on the
 * engine that produced them: switching engines re-translates rather than printing the old one's
 * words. If translation fails the English set comes back and nothing is cached, so a document always
 * renders and the next attempt tries again.
 *
 * <p>This is the general form of {@link PurchaseOrderLabelTranslator}, which came first and still
 * keeps its own table. Folding that one in here is a small piece of work left deliberately undone —
 * see V64's note — rather than done in passing during a build with other work in flight.
 */
@Service
public class DocumentLabelTranslator {

	private static final Logger log = LoggerFactory.getLogger(DocumentLabelTranslator.class);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final GlossaryService glossaryService;
	private final TranslationProvider provider;

	public DocumentLabelTranslator(
			JdbcTemplate jdbc, ObjectMapper objectMapper, GlossaryService glossaryService,
			TranslationProvider provider) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.glossaryService = glossaryService;
		this.provider = provider;
	}

	/**
	 * One label set in one language, in the template's own fixed order. English needs no translation
	 * and never reaches the provider.
	 *
	 * @param labelSet which document's labels these are, e.g. {@code JOB_CARD}
	 * @param version  bumped by the template when its English labels change
	 * @param english  the source set, in order
	 */
	@Transactional
	public List<String> labels(String labelSet, int version, List<String> english, String language) {
		if (isEnglish(language)) {
			return english;
		}
		List<String> cached = readCache(labelSet, version, language);
		if (cached != null && cached.size() == english.size()) {
			return cached;
		}
		try {
			List<String> translated = translate(english, language);
			writeCache(labelSet, version, language, translated);
			return translated;
		} catch (RuntimeException e) {
			// A document must always render; fall back to English and let the next request retry.
			log.warn("Label translation of {} to {} failed; falling back to English", labelSet, language, e);
			return english;
		}
	}

	private List<String> translate(List<String> source, String language) {
		Map<String, String> glossary = glossaryService.lookup(language);
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

	private List<String> readCache(String labelSet, int version, String language) {
		List<String> rows = jdbc.query("""
				SELECT content FROM document_label_translations
				WHERE label_set = ? AND language = ? AND label_set_version = ? AND provider = ?
				""", (rs, n) -> rs.getString("content"), labelSet, language, version, provider.name());
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

	private void writeCache(String labelSet, int version, String language, List<String> labels) {
		String json;
		try {
			json = objectMapper.writeValueAsString(labels);
		} catch (JsonProcessingException e) {
			return; // Failing to cache is not worth failing the render over.
		}
		jdbc.update("""
				INSERT INTO document_label_translations (
					tenant_id, label_set, language, label_set_version, provider, content)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, CAST(? AS jsonb))
				ON CONFLICT (tenant_id, label_set, language, label_set_version)
				DO UPDATE SET content = EXCLUDED.content, provider = EXCLUDED.provider, updated_at = now()
				""", labelSet, language, version, provider.name(), json);
	}

	static boolean isEnglish(String language) {
		return language == null || language.isBlank() || "en".equalsIgnoreCase(language);
	}
}
