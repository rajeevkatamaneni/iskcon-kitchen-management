package org.iskcon.kms.translation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives every stock item a name in the temple's glossary, translating by machine only the ones
 * nobody has named (E10-T8c).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The Kitchen Assistant answers in Kannada. Left to name stock itself, the model spelt 12 of 28
 * items more than one way across five repeats of one question (docs/work/proof/E10-T8b.md). A cook
 * matching a word to a sack needs the same word every time, so Rajeev decided on 2026-10-04 that
 * names come from the app, never the model. Nothing in the app stored a machine name on its own —
 * recipes, purchase orders and work orders each translate afresh — so this makes the glossary the
 * store: one row per item, written once, read by everything after.
 *
 * <h2>Why a one-off action and not a lookup that fills as it goes</h2>
 *
 * <p>The assistant's tools are read-only, and should stay so: a translation inside a tool call
 * would be a paid network call and a database write on a path that today does neither, on behalf
 * of a model. So the fill is something a person with the glossary's own permission runs, and the
 * tools only ever read what it left.
 *
 * <h2>The rules it keeps</h2>
 *
 * <ul>
 *   <li><b>Never overwrites.</b> An item with any entry in the language — typed by a person or
 *       written by an earlier fill — is skipped, and the insert is {@code ON CONFLICT DO NOTHING}
 *       besides, so two fills racing cannot replace each other's rows either. A machine name is
 *       stable from the moment it is written until a person corrects it.
 *   <li><b>Safe to run twice.</b> The second run finds nothing missing and calls no translator.
 *   <li><b>Marked as the machine's.</b> Each row carries {@code machine_translated_at} and
 *       {@code machine_provider} (V161), so a later screen can list the names nobody has checked.
 *       That matters: Google rendered "mace" as the weapon when this was built.
 *   <li><b>The machine hears the name read aloud.</b> {@link Translatable#ingredientName} sends
 *       "Rice, broken" as "broken rice" while the glossary row is keyed on the name as filed, which
 *       is the key every reader looks it up by.
 *   <li><b>One temple.</b> Every statement runs on the caller's connection under the table's
 *       row-level security; nothing here names a tenant except the insert, which takes it from the
 *       session setting the verified token put there, as {@link GlossaryService#upsert} does.
 * </ul>
 *
 * <p>Not audited: no glossary change is today (adding, correcting and deleting a term write no
 * audit entry), and the rows themselves carry who-wrote-it in the machine mark.
 *
 * <p>The translation call happens inside the transaction. That holds one connection for the length
 * of one batched request, once, when a person presses a button, and in exchange a failed
 * translation leaves the glossary exactly as it was.
 */
@Service
public class StockNameFillService {

	/**
	 * Strings per translation request. Google's v3 limit is 1,024 strings and 30,000 code points per
	 * call; stock names are short, and a temple has about 120 of them, so this is one call in
	 * practice and a guard against a catalogue that grows.
	 */
	static final int BATCH = 100;

	private final JdbcTemplate jdbc;
	private final TranslationProvider provider;

	public StockNameFillService(JdbcTemplate jdbc, TranslationProvider provider) {
		this.jdbc = jdbc;
		this.provider = provider;
	}

	/**
	 * Translates and stores the name of every stock item that has no glossary entry in
	 * {@code language}.
	 *
	 * <p>"Stock item" is every row of {@code inventory_items} — the list the stock screen and the
	 * assistant's {@code get_inventory} read — not every ingredient in the recipe catalogue, which is
	 * thousands of names nobody is asking about.
	 */
	@Transactional
	public FillResult fillStockNames(String language) {
		// The filed names with no entry, compared as the glossary's unique key compares them
		// (lower(source_term)). DISTINCT ON the same key, because two items filed "Rice" and
		// "rice" would be one glossary row and a second insert would just be dropped.
		List<String> missing = jdbc.queryForList("""
				SELECT DISTINCT ON (lower(i.name)) i.name
				FROM inventory_items ii
				JOIN ingredients i ON i.id = ii.ingredient_id
				WHERE NOT EXISTS (
					SELECT 1 FROM translation_glossary g
					WHERE g.language = ? AND lower(g.source_term) = lower(i.name))
				ORDER BY lower(i.name), i.name
				""", String.class, language);
		Integer stock = jdbc.queryForObject("""
				SELECT count(DISTINCT lower(i.name))
				FROM inventory_items ii
				JOIN ingredients i ON i.id = ii.ingredient_id
				""", Integer.class);
		int total = stock == null ? 0 : stock;
		if (missing.isEmpty()) {
			return new FillResult(language, total, 0, provider.name());
		}

		Map<String, String> named = translate(missing, language);
		int inserted = 0;
		for (Map.Entry<String, String> name : named.entrySet()) {
			inserted += jdbc.update("""
					INSERT INTO translation_glossary (
						tenant_id, language, source_term, target_term, machine_translated_at, machine_provider)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, now(), ?)
					ON CONFLICT (tenant_id, language, lower(source_term)) DO NOTHING
					""", language, name.getKey(), name.getValue(), provider.name());
		}
		return new FillResult(language, total, inserted, provider.name());
	}

	/** Filed name to translation, in batches, skipping any the engine hands back blank. */
	private Map<String, String> translate(List<String> filedNames, String language) {
		Map<String, String> out = new LinkedHashMap<>();
		for (int from = 0; from < filedNames.size(); from += BATCH) {
			List<String> batch = filedNames.subList(from, Math.min(from + BATCH, filedNames.size()));
			List<String> spoken = new ArrayList<>(batch.size());
			for (String filed : batch) {
				spoken.add(Translatable.ingredientName(filed).forMachine());
			}
			List<String> translated = provider.translate(spoken, Languages.ENGLISH, language);
			for (int i = 0; i < batch.size() && i < translated.size(); i++) {
				String target = translated.get(i) == null ? "" : translated.get(i).strip();
				// The table refuses an empty target, and one bad string should not lose the rest:
				// a blank is left unnamed and the next fill tries it again.
				if (!target.isEmpty()) {
					out.put(batch.get(i), target);
				}
			}
		}
		return out;
	}

	/**
	 * What one fill did. {@code stockItems} is how many distinct stock names the temple has;
	 * {@code added} how many this run named; {@code provider} which engine wrote them.
	 */
	public record FillResult(String language, int stockItems, int added, String provider) {
	}
}
