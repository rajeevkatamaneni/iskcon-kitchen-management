package org.iskcon.kms.ingredient.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.ingredient.merge.IngredientMergeService.Rule;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two things about the merge that are properties of the schema rather than of one merge:
 *
 * <ol>
 *   <li><b>Every column that points at an ingredient has a rule.</b> The catalogue is asked, not a
 *       list: a migration that adds a table with an ingredient foreign key fails here until
 *       {@link IngredientMergeService#RULES} says what a merge does with it — and the merge itself
 *       refuses to run until then, rather than leaving the table's rows behind.</li>
 *   <li><b>V147 opened one door and no other.</b> The append-only ledgers still refuse an ordinary
 *       UPDATE or DELETE from the application; {@code merge_ingredient_ledger_rows} re-points only,
 *       only this temple's rows, only between two ingredients of this temple of one kind of unit, and
 *       only on the four ledgers it names.</li>
 * </ol>
 *
 * <p>Run through the application's own DataSource as {@code kms_app}, with the temple set the way a
 * request sets it, so what is refused is refused to the role that actually runs.
 */
class IngredientMergeCatalogueIT extends AbstractIntegrationTest {

	private static final String TEMPLE_A = "merge-catalogue-a";
	private static final String TEMPLE_B = "merge-catalogue-b";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private IngredientReferences references;

	private JdbcTemplate app;
	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		app = new JdbcTemplate(dataSource);
		admin = new JdbcTemplate(adminDataSource());
		MergeFixtures.purge(admin, TEMPLE_A);
		MergeFixtures.purge(admin, TEMPLE_B);
		templeA = MergeFixtures.temple(admin, TEMPLE_A, "uid-merge-cat-a", null);
		templeB = MergeFixtures.temple(admin, TEMPLE_B, "uid-merge-cat-b", null);
		MergeFixtures.curdGroup(admin, templeA);
		MergeFixtures.curdGroup(admin, templeB);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		MergeFixtures.purge(admin, TEMPLE_A);
		MergeFixtures.purge(admin, TEMPLE_B);
	}

	// =====================================================================
	// The catalogue
	// =====================================================================

	@Test
	@DisplayName("every foreign key to ingredients in the catalogue has a merge rule, and every rule a foreign key")
	void everyReferenceHasARule() {
		List<String> catalogue = admin.queryForList("""
				SELECT rel.relname || '.' || att.attname
				FROM pg_constraint con
				JOIN pg_class rel ON rel.oid = con.conrelid
				JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
				WHERE con.contype = 'f' AND con.confrelid = 'public.ingredients'::regclass
				ORDER BY 1
				""", String.class);

		assertThat(catalogue)
				.as("""
						A table points at ingredients that the duplicate-ingredient merge (R-DUP-3) has no
						rule for, or a rule names a column that no longer exists. Add the column to
						IngredientMergeService.RULES with what a merge must do to its rows — and, if the
						table is append-only, to merge_ingredient_ledger_rows in a migration.""")
				.containsExactlyInAnyOrderElementsOf(IngredientMergeService.RULES.keySet());
		// The component the merge actually uses agrees with the query above.
		assertThat(references.all().stream().map(IngredientReferences.Reference::key).toList())
				.containsExactlyInAnyOrderElementsOf(catalogue);
		// §9A says "seven tables" and names eight; the schema has thirteen, because V144 added five.
		assertThat(catalogue).hasSize(13);
	}

	@Test
	@DisplayName("the append-only tables among them are exactly the ones the merge re-points through V147")
	void appendOnlyTablesAreTheLedgers() {
		Map<String, Boolean> appendOnly = references.all().stream()
				.collect(Collectors.toMap(IngredientReferences.Reference::key, IngredientReferences.Reference::appendOnly));
		appendOnly.forEach((key, isAppendOnly) -> assertThat(IngredientMergeService.RULES.get(key) == Rule.LEDGER)
				.as("%s is append-only exactly when its rule is LEDGER", key)
				.isEqualTo(isAppendOnly));
		assertThat(appendOnly.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).sorted().toList())
				.containsExactly("goods_receipt_lines.ingredient_id", "ingredient_market_rate_history.ingredient_id",
						"stock_movements.ingredient_id", "vendor_price_history.ingredient_id");
	}

	@Test
	@DisplayName("the rows that name a pack follow it when a duplicate pack folds: the three pack columns")
	void packColumnsAreKnown() {
		assertThat(references.packColumns()).containsExactly(
				"purchase_order_lines.pack_size_id", "vendor_invoice_lines.pack_size_id", "vendor_supplies.pack_size_id");
	}

	// =====================================================================
	// V147: the door, and only the door
	// =====================================================================

	@Test
	@DisplayName("after V147, an ordinary UPDATE or DELETE on every append-only ledger with an ingredient is still refused")
	void theLedgersAreStillAppendOnly() {
		TenantContext.set(templeA);
		List<String> ledgers = references.all().stream().filter(IngredientReferences.Reference::appendOnly)
				.map(IngredientReferences.Reference::table).toList();
		assertThat(ledgers).hasSize(4);
		UUID curd = MergeFixtures.ingredient(admin, templeA, "Curd");
		for (String table : ledgers) {
			assertThat(app.queryForObject("SELECT count(*) FROM " + table, Integer.class))
					.as("the fixture gives temple A rows in %s", table).isPositive();
			assertThatThrownBy(() -> app.update("UPDATE " + table + " SET ingredient_id = ?", curd))
					.as("re-pointing %s by hand", table).hasStackTraceContaining("append-only");
			assertThatThrownBy(() -> app.update("UPDATE " + table + " SET ingredient_id = ingredient_id"))
					.as("a no-op UPDATE on %s", table).hasStackTraceContaining("append-only");
			assertThatThrownBy(() -> app.update("DELETE FROM " + table))
					.as("deleting from %s", table).hasStackTraceContaining("append-only");
		}
	}

	@Test
	@DisplayName("the function re-points this temple's rows of one ingredient onto another, converting a per-gm rate to per-Kg")
	void theFunctionRepointsAndConverts() {
		TenantContext.set(templeA);
		UUID curd = MergeFixtures.ingredient(admin, templeA, "Curd");
		UUID sour = MergeFixtures.ingredient(admin, templeA, "Curd, sour");
		UUID theirSour = MergeFixtures.ingredient(admin, templeB, "Curd, sour");
		String theirsBefore = ledgerRows(templeB);

		assertThat(app.queryForObject("SELECT merge_ingredient_ledger_rows('vendor_price_history', ?, ?)",
				Long.class, sour, curd)).isEqualTo(2L);
		assertThat(app.queryForObject("SELECT merge_ingredient_ledger_rows('stock_movements', ?, ?)",
				Long.class, sour, curd)).isEqualTo(1L);

		assertThat(admin.queryForList(
				"SELECT price_per_unit::text FROM vendor_price_history WHERE ingredient_id = ? ORDER BY 1",
				String.class, curd)).containsExactly("60.0000", "65.0000", "65.0000");
		assertThat(admin.queryForObject(
				"SELECT quantity || ' ' || unit FROM stock_movements WHERE ingredient_id = ? AND movement_type = 'PO_RECEIPT'",
				String.class, curd)).as("a movement keeps the quantity and unit it was recorded in").isEqualTo("1500.000 GM");
		// Only the moved columns changed: everything else on the row is as it was written.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM vendor_price_history WHERE ingredient_id = ? AND source = 'INVOICE' AND vendor_invoice_line_id IS NOT NULL",
				Integer.class, curd)).isEqualTo(1);
		assertThat(ledgerRows(templeB)).as("temple B's ledgers are untouched").isEqualTo(theirsBefore);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ?", Integer.class, theirSour)).isEqualTo(1);
	}

	@Test
	@DisplayName("the function refuses: another temple's ingredient, the same ingredient twice, a mixed kind of unit, a table it does not know, no temple")
	void theFunctionRefusesEverythingElse() {
		UUID curd = MergeFixtures.ingredient(admin, templeA, "Curd");
		UUID sour = MergeFixtures.ingredient(admin, templeA, "Curd, sour");
		UUID theirCurd = MergeFixtures.ingredient(admin, templeB, "Curd");
		UUID pieces = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Curd cups', 'Dairy', 'PIECES') RETURNING id
				""", UUID.class, templeA);
		String before = ledgerRows(templeA) + ledgerRows(templeB);

		TenantContext.set(templeA);
		assertThatThrownBy(() -> call("stock_movements", sour, theirCurd))
				.hasStackTraceContaining("not found for this temple");
		assertThatThrownBy(() -> call("stock_movements", theirCurd, curd))
				.hasStackTraceContaining("not found for this temple");
		assertThatThrownBy(() -> call("stock_movements", curd, curd))
				.hasStackTraceContaining("two different ingredients");
		assertThatThrownBy(() -> call("stock_movements", sour, pieces))
				.hasStackTraceContaining("different kinds of unit");
		for (String table : List.of("audit_events", "recipe_ingredients", "stock_movements; DELETE FROM stock_movements")) {
			assertThatThrownBy(() -> call(table, sour, curd))
					.as("naming %s", table).hasStackTraceContaining("is not a ledger the merge knows");
		}
		TenantContext.clear();
		assertThatThrownBy(() -> call("stock_movements", sour, curd))
				.hasStackTraceContaining("no temple in this session");

		assertThat(ledgerRows(templeA) + ledgerRows(templeB)).as("every refusal wrote nothing").isEqualTo(before);
	}

	@Test
	@DisplayName("the function is SECURITY DEFINER with a pinned search_path, and not executable by PUBLIC")
	void theFunctionIsLockedDown() {
		Map<String, Object> fn = admin.queryForMap("""
				SELECT p.prosecdef, array_to_string(p.proconfig, ',') AS config, coalesce(p.proacl::text, '') AS acl,
					   has_function_privilege('kms_app', p.oid, 'EXECUTE') AS app_may
				FROM pg_proc p WHERE p.proname = 'merge_ingredient_ledger_rows'
				""");
		assertThat(fn.get("prosecdef")).isEqualTo(true);
		assertThat((String) fn.get("config")).isEqualTo("search_path=pg_catalog, public");
		assertThat((String) fn.get("acl")).as("no PUBLIC entry (\"=X/owner\")").doesNotContain("{=X").doesNotContain(",=X");
		assertThat(fn.get("app_may")).isEqualTo(true);
	}

	// ---------------------------------------------------------------------

	private Long call(String table, UUID from, UUID to) {
		return app.queryForObject("SELECT merge_ingredient_ledger_rows(?, ?, ?)", Long.class, table, from, to);
	}

	/** Every row of the four ledgers for one temple, as text, in a stable order. */
	private String ledgerRows(UUID temple) {
		StringBuilder s = new StringBuilder();
		for (String table : List.of("stock_movements", "goods_receipt_lines", "vendor_price_history",
				"ingredient_market_rate_history")) {
			admin.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t WHERE tenant_id = ? ORDER BY id",
					String.class, temple).forEach(r -> s.append(r).append('\n'));
		}
		return s.toString();
	}
}
