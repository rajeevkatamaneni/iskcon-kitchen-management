package org.iskcon.kms.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the curated recipe books and writes them into {@code master_recipes} (E2-S9).
 *
 * <p>The books live at {@code src/main/resources/recipe-library/} — two state files, karnataka (42
 * recipes) and andhra_pradesh (2), 44 in all, 454 ingredient lines. They are generated from the
 * recipes Rajeev approved one at a time on 2026-09-19 by {@code tools/seed/02b-build-catalogue.mjs}
 * and committed rather than fetched: a loader that reaches across to another repository during a
 * deployment is a deployment that can fail on somebody else's branch name. The files are the
 * converter's output and nobody hand-edits them; the editing source of truth is his curated
 * per-recipe files, and <em>this</em> is where the transformation into rows happens.
 *
 * <p>Until 2026-09-19 the directory held 32 books vendored byte-for-byte from another repository —
 * 5,376 recipes, 168 a book, none of them vetted. {@code recipe-library/README.md} keeps that
 * provenance; every figure below counts the curated catalogue unless it says otherwise.
 *
 * <h2>The local-language fields are dropped here and nowhere else</h2>
 *
 * <p>Every book carries a full translation alongside the English — the name, the subtitle, every
 * ingredient, every method step. None of it is stored. A book's language follows the state the
 * recipe came from and never the cook reading it: ISKCON Bangalore's kitchen has Bengali and Bihari
 * cooks, and the Karnataka book's Kannada serves neither of them. The cook who needs a recipe in
 * their own language is served by E2-S6, which translates on demand into <em>theirs</em>.
 *
 * <h2>Names are disambiguated in two passes, not one</h2>
 *
 * <p>No two recipes in today's catalogue share a name — all 44 keep their plain one, counted from
 * the books on 2026-09-19 — and the ladder below still runs on every load. It is not dead code kept
 * for sentiment. A temple may hold only one active recipe of a given name, so a collision has to be
 * settled by the library before any temple sees it, and a catalogue Rajeev adds to book by book will
 * produce one the first time two states send up their own Sabudana Khichdi. The vendored library
 * this replaced had 1,272 such names, seventeen books carrying a Sabudana Khichdi between them, and
 * Bihar's, Maharashtra's and Uttar Pradesh's were three different dishes — 5 Kg of sago against 7,
 * one sweetened, one yielding kilos rather than litres. That is the shape of the problem the two
 * passes exist for. {@code RecipeLibraryIT} proves them against a fixture book, because the real
 * catalogue no longer collides with itself.
 *
 * <p>The obvious way to do that is wrong, and wrong quietly. "Suffix it if I have seen this name
 * before" is a decision made while streaming, and the <em>first</em> Sabudana Khichdi has not been
 * seen before — so sixteen get a state and one does not, and which one escapes depends on the order
 * the files happened to be read in. Counting first and deciding second removes the question:
 *
 * <pre>
 *   pass 1   count every name across every book, decide nothing
 *   pass 2   suffix every recipe whose name is held by more than one, the first included
 * </pre>
 *
 * <p>A third rung exists for the case the state does not separate either: the same name twice in one
 * book, under two categories. The vendored library had exactly two such rows — Alugadde Palya
 * appeared twice in the Karnataka book, once under Ekadashi with rock salt and no mustard, once
 * under Sabji's Dry with a full tempering — so the category is added as well. The curated catalogue
 * keeps only the Ekadashi one, and nothing in it currently needs this rung.
 *
 * <h2>It stops rather than skips</h2>
 *
 * <p>A quantity that will not parse fails the whole load, naming the file and the recipe. A recipe
 * silently omitted is a hole nobody notices; a recipe silently mis-parsed is a kitchen cooking the
 * wrong amount. Neither is worth a load that finishes.
 */
@Component
public class LibraryLoader {

	private static final Logger log = LoggerFactory.getLogger(LibraryLoader.class);

	/**
	 * Where the books came from, stamped on every row as {@code SOURCE + ":" + filename} — so a row
	 * reads {@code tools/seed/02b-build-catalogue.mjs@2026-09-19:karnataka.json}.
	 *
	 * <p>It names the converter that wrote the books and the day Rajeev approved the recipes in them,
	 * which together are the whole provenance: the converter is in this repository and its input is
	 * his per-recipe approvals in {@code docs/work/reference/curated-recipes/}. It is deliberately not
	 * a commit of another repository any more — until 2026-09-19 this said
	 * {@code kranthimj23/ikms@41cf173}, which was true of the vendored books and is false of these.
	 *
	 * <p><strong>Change this whenever the catalogue is rebuilt from a new round of approvals</strong>,
	 * together with the table in {@code recipe-library/README.md}. A row that cannot be traced back to
	 * what produced it is a row nobody can check. Rows written before a change keep the stamp they
	 * were written with, and a re-load rewrites them, because the upsert updates in place.
	 */
	private static final String SOURCE = "tools/seed/02b-build-catalogue.mjs@2026-09-19";

	private static final String BOOKS = "classpath:recipe-library/*.json";

	private static final int BATCH = 250;

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final org.springframework.transaction.support.TransactionTemplate transactions;

	public LibraryLoader(
			JdbcTemplate jdbc,
			ObjectMapper mapper,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
	}

	/** What a load did, for the log line and the operator's response. */
	public record Result(int books, int recipes, int bare, int withState, int withStateAndCategory) {
	}

	/**
	 * Loads every book. Idempotent: the upsert keys on {@code (state_slug, recipe_slug)}, so running
	 * this twice leaves 44 rows rather than 88, and a corrected book updates in place.
	 *
	 * <p>Runs in one transaction. A partial library is harder to reason about than none — a temple
	 * searching mid-load would find some of Karnataka and none of Andhra Pradesh with nothing on the
	 * screen to say so.
	 */
	/**
	 * <p>Deliberately not {@code @Transactional}. The flag that lets this write has to be on the
	 * thread <em>before</em> the transaction opens, because the connection reads it once at checkout
	 * and a setting arriving afterwards reaches nothing — the load would then be refused by its own
	 * policy, which is what happened the first time this was written.
	 */
	public Result load() {
		return load(BOOKS);
	}

	/**
	 * Loads the books matching an Ant pattern. Production always loads {@link #BOOKS} and nothing
	 * else; this exists so a test can hand the reader one small book of its own.
	 *
	 * <p>{@link RecipeLibraryIT} loads the real catalogue and is the test that matters for everything
	 * the real files do say — its 44 recipes, its 83 preparations and its 15 never-bought marks all
	 * reach the table there. A fixture is for the two things the catalogue cannot show. One is a
	 * collision: no name in it repeats, so the disambiguation ladder is driven from
	 * {@code src/test/resources/ladder-book}, two small books that do repeat one. The other is the
	 * older shape, a preparation still written inside the ingredient's name after a comma ("Green
	 * chilli, slit") with no {@code prep} key at all — gone from the catalogue, still supported by the
	 * reader, and covered by {@code src/test/resources/prep-book}. Both would otherwise go untested
	 * the day the catalogue is regenerated.
	 */
	Result load(String books) {
		TenantContext.setLibraryLoad();
		try {
			return transactions.execute(status -> loadWithin(books));
		} finally {
			TenantContext.clearLibraryLoad();
		}
	}

	private Result loadWithin(String books) {
		{
			List<Row> rows = readAll(books);
			disambiguate(rows);
			upsert(rows);

			Result result = new Result(
					(int) rows.stream().map(Row::stateSlug).distinct().count(),
					rows.size(),
					(int) rows.stream().filter(r -> r.rung == 0).count(),
					(int) rows.stream().filter(r -> r.rung == 1).count(),
					(int) rows.stream().filter(r -> r.rung == 2).count());
			log.info("Recipe library loaded: {} recipes from {} books ({} bare, {} + state, {} + state and category)",
					result.recipes(), result.books(), result.bare(), result.withState(), result.withStateAndCategory());
			return result;
		}
	}

	// ------------------------------------------------------------------ reading

	private List<Row> readAll(String pattern) {
		Resource[] books;
		try {
			books = new PathMatchingResourcePatternResolver().getResources(pattern);
		} catch (IOException e) {
			throw new IllegalStateException("Could not list the recipe books at " + pattern, e);
		}
		if (books.length == 0) {
			throw new IllegalStateException(
					"No recipe books found at " + pattern + ". They are committed under "
							+ "backend/src/main/resources/recipe-library — check an ignore rule has not eaten them.");
		}

		// Sorted so a load is reproducible and a diff of two runs is readable. The two-pass
		// disambiguation does not depend on this; it is for the humans.
		List<Resource> ordered = new ArrayList<>(List.of(books));
		ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));

		List<Row> rows = new ArrayList<>();
		for (Resource book : ordered) {
			rows.addAll(readBook(book));
		}
		return rows;
	}

	private List<Row> readBook(Resource book) {
		String filename = String.valueOf(book.getFilename());
		JsonNode root;
		try (InputStream in = book.getInputStream()) {
			root = mapper.readTree(in);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read the recipe book " + filename, e);
		}

		String stateSlug = text(root, "slug");
		String state = text(root, "state");
		String language = text(root, "language");

		Map<String, String> categoryNames = new HashMap<>();
		for (JsonNode c : root.path("categories")) {
			categoryNames.put(text(c, "key"), text(c, "name"));
		}

		List<Row> rows = new ArrayList<>();
		for (JsonNode r : root.path("recipes")) {
			rows.add(readRecipe(filename, stateSlug, state, language, categoryNames, r));
		}
		return rows;
	}

	private Row readRecipe(
			String filename, String stateSlug, String state, String language,
			Map<String, String> categoryNames, JsonNode r) {

		String name = text(r, "name");
		String where = filename + " / " + name;

		String yieldText = text(r, "yield");
		BookParser.Quantity yield = BookParser.parseYield(yieldText).orElseThrow(() -> new IllegalStateException(
				"Could not read a yield from \"" + yieldText + "\" in " + where));

		Optional<BookParser.Quantity> perHead =
				BookParser.perHead(text(r, "per"), yieldText, yield.unit());

		List<String> ingredientNames = new ArrayList<>();
		List<Map<String, Object>> ingredients = new ArrayList<>();
		for (JsonNode i : r.path("ing")) {
			String ingredientName = text(i, "name");
			String qty = text(i, "qty");
			BookParser.LineQuantity parsed = BookParser.ingredientQuantity(qty)
					.orElseThrow(() -> new IllegalStateException(
							"Could not read the quantity \"" + qty + "\" for " + ingredientName + " in " + where));

			ingredientNames.add(ingredientName);

			// The local-language name and unit are deliberately not carried across. `scaled` is,
			// where the book precomputed it: it is the book's own arithmetic at 50, 100, 250 and
			// 500 devotees, and showing it costs nothing. No line of the curated catalogue carries
			// one — the vendored books did, on 19,356 lines — so this reads null on all 454 of
			// today's lines and is kept for a book that computes them again.
			//
			// `prep` is carried too, as of T-401. It is what the cook does to the line — "Slit",
			// "Roasted", "Soaked overnight" — and the books used to say it inside the name, after a
			// comma ("Green chilli, slit"), which is why nothing here ever stored it: the import
			// split the name again at the far end and recovered it. Rajeev's curated recipes
			// (2026-09-19) name the ingredient plainly and put the preparation in `prep`, so there
			// is no comma left to split and this key is now the only record of the 83 such notes in
			// the catalogue.
			// Written even when the book has none, as an explicit null: a line whose key is absent
			// and a line whose preparation is genuinely nothing would otherwise read alike, and the
			// reader downstream could not tell an old row from a new one.
			//
			// `not_bought` is carried as of T-403, and is the only line field that is a statement
			// about the *ingredient* rather than about this line: the temple never buys it at all.
			// Fifteen lines of Rajeev's curated set say it, every one of them water. An ingredient
			// the import creates from such a line is created with `ingredients.is_not_bought` set
			// (V153), which is what keeps water off every shopping list for good instead of being
			// unticked again on each one. Written as a real true/false on every line rather than
			// only where the book says true, for the reason `prep` is written as an explicit null:
			// a missing key and a false would read alike, and "the book did not say" is a different
			// thing from "the book said no". Both spellings are the book's own, kept as the book
			// writes them; the API renames it to `notBought` on the way out, where the rest of the
			// product spells it that way.
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("name", ingredientName);
			line.put("qty", qty);
			line.put("qtyValue", parsed.value());
			line.put("qtyUnit", parsed.unit());
			line.put("prep", text(i, "prep"));
			line.put("not_bought", i.path("not_bought").asBoolean(false));
			if (i.hasNonNull("scaled")) {
				line.put("scaled", mapper.convertValue(i.get("scaled"), Map.class));
			}
			ingredients.add(line);
		}
		if (ingredients.isEmpty()) {
			throw new IllegalStateException("No ingredients in " + where);
		}

		List<String> method = new ArrayList<>();
		for (JsonNode step : r.path("method")) {
			method.add(text(step, "en"));
		}

		JsonNode notes = r.path("notes");

		Row row = new Row();
		row.stateSlug = stateSlug;
		row.state = state;
		row.language = language;
		row.recipeSlug = text(r, "slug");
		row.name = name;
		row.subtitle = text(r, "sub");
		row.categoryKey = text(r, "cat");
		row.categoryName = categoryNames.getOrDefault(row.categoryKey, row.categoryKey);
		row.badge = text(r, "badge");
		row.yieldText = yieldText;
		row.yieldQty = yield.value();
		row.yieldUnit = yield.unit();
		row.perHeadText = text(r, "per");
		row.perHeadQty = perHead.map(BookParser.Quantity::value).orElse(null);
		row.perHeadUnit = perHead.map(BookParser.Quantity::unit).orElse(null);
		row.cost = r.hasNonNull("cost") ? new BigDecimal(r.get("cost").asText()) : null;
		row.region = text(r, "region");
		row.why = text(r, "why");
		row.cateringNote = text(r, "catering");
		row.noteStart = text(notes.path("start"), "en");
		row.noteVessel = text(notes.path("vessel"), "en");
		row.noteSeason = text(notes.path("season"), "en");
		row.tags = strings(r.path("tags"));
		row.serveWith = strings(r.path("serve_with"));
		row.ingredientNames = ingredientNames;
		row.ingredients = write(ingredients);
		row.method = write(method);
		row.sourceRef = SOURCE + ":" + filename;
		return row;
	}

	// ------------------------------------------------------------------ the ladder

	/**
	 * Assigns every row its {@code display_name}, counting before deciding. See the class comment
	 * for why the order of those two matters.
	 */
	private void disambiguate(List<Row> rows) {
		Map<String, Integer> byName = new HashMap<>();
		for (Row row : rows) {
			byName.merge(row.name.toLowerCase(Locale.ROOT), 1, Integer::sum);
		}

		Map<String, Integer> byNameAndState = new HashMap<>();
		for (Row row : rows) {
			if (byName.get(row.name.toLowerCase(Locale.ROOT)) > 1) {
				byNameAndState.merge(withState(row).toLowerCase(Locale.ROOT), 1, Integer::sum);
			}
		}

		for (Row row : rows) {
			if (byName.get(row.name.toLowerCase(Locale.ROOT)) == 1) {
				row.displayName = row.name;
				row.rung = 0;
			} else if (byNameAndState.get(withState(row).toLowerCase(Locale.ROOT)) == 1) {
				row.displayName = withState(row);
				row.rung = 1;
			} else {
				row.displayName = "%s (%s, %s)".formatted(row.name, row.state, row.categoryName);
				row.rung = 2;
			}
		}
	}

	private static String withState(Row row) {
		return "%s (%s)".formatted(row.name, row.state);
	}

	// ------------------------------------------------------------------ writing

	private void upsert(List<Row> rows) {
		String sql = """
				INSERT INTO master_recipes (
					state_slug, state, book_language, recipe_slug,
					name, display_name, disambiguated_by, subtitle,
					category_key, category_name, badge,
					yield_text, yield_qty, yield_unit,
					per_head_text, per_head_qty, per_head_unit,
					indicative_cost, region, why, catering_note,
					note_start, note_vessel, note_season,
					tags, serve_with, ingredient_names,
					ingredients, method, source_ref)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
					?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
				ON CONFLICT (state_slug, recipe_slug) DO UPDATE SET
					state = EXCLUDED.state,
					book_language = EXCLUDED.book_language,
					name = EXCLUDED.name,
					display_name = EXCLUDED.display_name,
					disambiguated_by = EXCLUDED.disambiguated_by,
					subtitle = EXCLUDED.subtitle,
					category_key = EXCLUDED.category_key,
					category_name = EXCLUDED.category_name,
					badge = EXCLUDED.badge,
					yield_text = EXCLUDED.yield_text,
					yield_qty = EXCLUDED.yield_qty,
					yield_unit = EXCLUDED.yield_unit,
					per_head_text = EXCLUDED.per_head_text,
					per_head_qty = EXCLUDED.per_head_qty,
					per_head_unit = EXCLUDED.per_head_unit,
					indicative_cost = EXCLUDED.indicative_cost,
					region = EXCLUDED.region,
					why = EXCLUDED.why,
					catering_note = EXCLUDED.catering_note,
					note_start = EXCLUDED.note_start,
					note_vessel = EXCLUDED.note_vessel,
					note_season = EXCLUDED.note_season,
					tags = EXCLUDED.tags,
					serve_with = EXCLUDED.serve_with,
					ingredient_names = EXCLUDED.ingredient_names,
					ingredients = EXCLUDED.ingredients,
					method = EXCLUDED.method,
					source_ref = EXCLUDED.source_ref,
					updated_at = now()
				""";

		for (int from = 0; from < rows.size(); from += BATCH) {
			List<Row> slice = rows.subList(from, Math.min(from + BATCH, rows.size()));
			jdbc.batchUpdate(sql, slice, slice.size(), (PreparedStatement ps, Row row) -> bind(ps, row));
		}
	}

	private static void bind(PreparedStatement ps, Row row) throws SQLException {
		int i = 1;
		ps.setString(i++, row.stateSlug);
		ps.setString(i++, row.state);
		ps.setString(i++, row.language);
		ps.setString(i++, row.recipeSlug);
		ps.setString(i++, row.name);
		ps.setString(i++, row.displayName);
		ps.setInt(i++, row.rung);
		ps.setString(i++, row.subtitle);
		ps.setString(i++, row.categoryKey);
		ps.setString(i++, row.categoryName);
		ps.setString(i++, row.badge);
		ps.setString(i++, row.yieldText);
		ps.setBigDecimal(i++, row.yieldQty);
		ps.setString(i++, row.yieldUnit);
		ps.setString(i++, row.perHeadText);
		if (row.perHeadQty == null) {
			ps.setNull(i++, Types.NUMERIC);
		} else {
			ps.setBigDecimal(i++, row.perHeadQty);
		}
		ps.setString(i++, row.perHeadUnit);
		if (row.cost == null) {
			ps.setNull(i++, Types.NUMERIC);
		} else {
			ps.setBigDecimal(i++, row.cost);
		}
		ps.setString(i++, row.region);
		ps.setString(i++, row.why);
		ps.setString(i++, row.cateringNote);
		ps.setString(i++, row.noteStart);
		ps.setString(i++, row.noteVessel);
		ps.setString(i++, row.noteSeason);
		ps.setArray(i++, ps.getConnection().createArrayOf("text", row.tags.toArray()));
		ps.setArray(i++, ps.getConnection().createArrayOf("text", row.serveWith.toArray()));
		ps.setArray(i++, ps.getConnection().createArrayOf("text", row.ingredientNames.toArray()));
		ps.setString(i++, row.ingredients);
		ps.setString(i++, row.method);
		ps.setString(i, row.sourceRef);
	}

	// ------------------------------------------------------------------ helpers

	/** A string field, or null where the book left it out or wrote a JSON null. */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String s = value.asText().trim();
		return s.isEmpty() ? null : s;
	}

	private static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		for (JsonNode n : array) {
			out.add(n.asText());
		}
		return out;
	}

	private String write(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (IOException e) {
			throw new IllegalStateException("Could not serialise a recipe fragment", e);
		}
	}

	/** One recipe on its way into the table. Mutable because the ladder fills two fields in a later pass. */
	private static final class Row {
		String stateSlug;
		String state;
		String language;
		String recipeSlug;
		String name;
		String displayName;
		int rung;
		String subtitle;
		String categoryKey;
		String categoryName;
		String badge;
		String yieldText;
		BigDecimal yieldQty;
		String yieldUnit;
		String perHeadText;
		BigDecimal perHeadQty;
		String perHeadUnit;
		BigDecimal cost;
		String region;
		String why;
		String cateringNote;
		String noteStart;
		String noteVessel;
		String noteSeason;
		List<String> tags;
		List<String> serveWith;
		List<String> ingredientNames;
		String ingredients;
		String method;
		String sourceRef;

		String stateSlug() {
			return stateSlug;
		}
	}
}
