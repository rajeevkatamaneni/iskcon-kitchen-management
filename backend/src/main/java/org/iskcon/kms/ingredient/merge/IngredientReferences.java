package org.iskcon.kms.ingredient.merge;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every column in the database that points at an ingredient, read from the catalogue rather than
 * from a list somebody wrote down.
 *
 * <p><b>Why the catalogue.</b> R-DUP-3 says "repoints every reference in the seven tables", and the
 * seven were correct the day the document was written. V144 then added five more —
 * {@code vendor_invoice_lines}, {@code vendor_price_history}, {@code ingredient_market_rate_history},
 * {@code ingredient_pack_sizes}, {@code ingredient_aliases} — and the next migration may add another.
 * A merge that re-points a hand-kept list leaves a row behind on the first table the list does not
 * know, and then either the delete of the merged-away ingredient is refused (a RESTRICT key) or,
 * worse, the row silently goes with it (a CASCADE key). So the merge asks {@code pg_constraint} what
 * points at {@code ingredients} on every run, and refuses to start if the answer holds a column it has
 * no rule for. {@code IngredientMergeCatalogueIT} asks the same question in the suite, so a new table
 * fails a test the day it is added rather than a merge the day somebody runs one.
 *
 * <p><b>Why the catalogue is readable here.</b> {@code pg_constraint} and {@code pg_attribute} are
 * readable by every role, and they describe the schema, not any temple's rows, so reading them as the
 * application role crosses no tenant boundary.
 */
@Component
public class IngredientReferences {

	/** One foreign-key column pointing at {@code ingredients(id)}. */
	public record Reference(String table, String column, boolean appendOnly) {

		/** "stock_movements.ingredient_id" — how the merge's own rules are keyed. */
		public String key() {
			return table + "." + column;
		}
	}

	private final JdbcTemplate jdbc;

	public IngredientReferences(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Every foreign-key column in {@code public} that references {@code ingredients}, ordered by table
	 * and column. {@code appendOnly} is whether the table carries make_append_only()'s trigger
	 * ({@code <table>_append_only}, V49), which is what decides that the merge must go through
	 * {@code merge_ingredient_ledger_rows} (V147) rather than a plain UPDATE.
	 */
	public List<Reference> all() {
		return jdbc.query("""
				SELECT rel.relname AS table_name, att.attname AS column_name,
					   EXISTS (SELECT 1 FROM pg_trigger t
							   WHERE t.tgrelid = con.conrelid
								 AND t.tgname = rel.relname || '_append_only'
								 AND NOT t.tgisinternal) AS append_only
				FROM pg_constraint con
				JOIN pg_class rel ON rel.oid = con.conrelid
				JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
				WHERE con.contype = 'f'
				  AND con.confrelid = 'public.ingredients'::regclass
				  AND rel.relnamespace = 'public'::regnamespace
				ORDER BY rel.relname, att.attname
				""", (rs, n) -> new Reference(
						rs.getString("table_name"), rs.getString("column_name"), rs.getBoolean("append_only")));
	}

	/**
	 * Every foreign-key column that references {@code ingredient_pack_sizes}, as "table.column" — the
	 * rows that must follow a pack when a duplicate pack is folded into the kept ingredient's.
	 * Composite keys (pack, ingredient) are reported by their pack column only.
	 */
	public List<String> packColumns() {
		return jdbc.queryForList("""
				SELECT rel.relname || '.' || att.attname AS ref
				FROM pg_constraint con
				JOIN pg_class rel ON rel.oid = con.conrelid
				JOIN pg_attribute att ON att.attrelid = con.conrelid
					AND att.attnum = con.conkey[array_position(con.confkey,
						(SELECT attnum FROM pg_attribute
						 WHERE attrelid = 'public.ingredient_pack_sizes'::regclass AND attname = 'id'))]
				WHERE con.contype = 'f'
				  AND con.confrelid = 'public.ingredient_pack_sizes'::regclass
				ORDER BY 1
				""", String.class);
	}

	/**
	 * How many rows of each referencing table point at any of {@code ingredientIds}, keyed by table
	 * (columns of one table summed). Run under the caller's row-level security, so it counts this
	 * temple's rows only — which is the question, since a merge only ever touches one temple.
	 */
	public Map<String, Long> countPointingAt(Collection<UUID> ingredientIds) {
		Map<String, Long> counts = new LinkedHashMap<>();
		UUID[] ids = ingredientIds.toArray(UUID[]::new);
		for (Reference ref : all()) {
			// Table and column names come from the catalogue, never from a request, and are quoted.
			Long n = jdbc.query(connection -> {
				var ps = connection.prepareStatement(
						"SELECT count(*) FROM " + quote(ref.table()) + " WHERE " + quote(ref.column()) + " = ANY (?)");
				ps.setArray(1, connection.createArrayOf("uuid", ids));
				return ps;
			}, rs -> rs.next() ? rs.getLong(1) : 0L);
			counts.merge(ref.table(), n == null ? 0L : n, Long::sum);
		}
		return counts;
	}

	/** The tables in {@code refs}, for messages and assertions. */
	static Set<String> keys(Collection<Reference> refs) {
		return refs.stream().map(Reference::key).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
	}

	static String quote(String identifier) {
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}
}
