package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code to_base_qty} — the one conversion (E11-S1, V74).
 *
 * <p>The arithmetic this function replaces was written out by hand in seven places, each ending
 * {@code ELSE 1}, which turned a unit nobody recognised into one gram rather than into an error. The
 * tests here hold the two properties that made it worth replacing: it agrees with {@link Unit} for
 * every unit that exists, and it refuses — loudly, as a NULL that survives a SUM — every unit that
 * does not.
 *
 * <p>There is no MockMvc and no signed-in person in this class on purpose. The subject is a database
 * function, and reaching it through a controller would test the controller.
 */
class BaseQuantityIT extends AbstractIntegrationTest {

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID ingredient;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('base-qty', 'Base Quantity Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		ingredient = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG')
				RETURNING id
				""", UUID.class, tenant);
	}

	@AfterEach
	void tearDown() {
		admin.update("DELETE FROM shopping_list_lines WHERE tenant_id = ?", tenant);
		admin.update("DELETE FROM ingredients WHERE tenant_id = ?", tenant);
		admin.update("DELETE FROM tenants WHERE id = ?", tenant);
	}

	@Test
	@DisplayName("the function agrees with the Unit enum for every unit that exists")
	void agreesWithTheEnum() {
		// Iterating the enum rather than listing the units by hand: a unit added to Java and
		// forgotten in SQL is exactly the drift this function exists to prevent, so the test has to
		// fail when that happens rather than keep passing against a stale list of its own. Every
		// member measures food now that servings has gone (V80), so there is nothing to filter out.
		for (Unit unit : Unit.values()) {
			BigDecimal fromSql = admin.queryForObject(
					"SELECT to_base_qty(3, ?)", BigDecimal.class, unit.name());
			BigDecimal fromJava = BigDecimal.valueOf(3L * unit.baseFactor());

			assertThat(fromSql)
					.as("to_base_qty disagrees with Unit.%s.baseFactor()", unit.name())
					.isNotNull()
					.usingComparator(BigDecimal::compareTo)
					.isEqualTo(fromJava);
		}
	}

	@Test
	@DisplayName("a unit nobody recognises raises, and names itself when it does")
	void unknownUnitRaises() {
		assertThatThrownBy(() -> admin.queryForObject("SELECT to_base_qty(5, 'FURLONGS')", BigDecimal.class))
				.hasMessageContaining("Unknown unit of measure")
				.hasMessageContaining("FURLONGS");
	}

	@Test
	@DisplayName("a unit that is missing altogether raises too")
	void nullUnitRaises() {
		// `NULL NOT IN (...)` is NULL rather than true, so a null unit slips through the obvious
		// spelling of this guard and returns null. It is checked separately because it was.
		assertThatThrownBy(() -> admin.queryForObject("SELECT to_base_qty(5, NULL)", BigDecimal.class))
				.hasMessageContaining("Unknown unit of measure");
	}

	@Test
	@DisplayName("a quantity may be unknown; what it is measured in may not")
	void nullQuantityIsFine() {
		assertThat(admin.queryForObject("SELECT to_base_qty(NULL, 'KG')", BigDecimal.class)).isNull();
	}

	@Test
	@DisplayName("one unrecognised row fails the whole sum rather than quietly vanishing from it")
	void unknownUnitFailsTheSum() {
		// The reason this function raises rather than returning NULL, and it was found by writing
		// the test the other way round first: SQL's SUM *skips* NULLs. Two kilos and one unreadable
		// row would have summed to 2000 — the bad row silently dropped, the total confidently wrong
		// and no indication anywhere that a row had been left out. Only an exception survives an
		// aggregate.
		assertThatThrownBy(() -> admin.queryForObject("""
				SELECT SUM(to_base_qty(q, u))
				FROM (VALUES (2, 'KG'), (5, 'FURLONGS')) AS v(q, u)
				""", BigDecimal.class))
				.hasMessageContaining("Unknown unit of measure");
	}

	@Test
	@DisplayName("the three columns that never had a CHECK now refuse a bad unit")
	void theUnconstrainedColumnsAreConstrained() {
		assertThatThrownBy(() -> insertShoppingListLine("FURLONGS"))
				.hasMessageContaining("shopping_list_lines_unit_valid");

		// And the constraint is not so eager that it refuses a real one.
		insertShoppingListLine("KG");
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM shopping_list_lines WHERE tenant_id = ?", Integer.class, tenant))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("no hand-written unit conversion survives anywhere in the source")
	void noHandWrittenConversionRemains() throws IOException {
		Path root = Files.isDirectory(Path.of("src")) ? Path.of("src") : Path.of("backend/src");

		try (Stream<Path> tree = Files.walk(root)) {
			List<String> offenders = tree
					.filter(p -> p.toString().endsWith(".java"))
					// This file names the pattern in order to look for it, so it would otherwise
					// be its own only offender.
					.filter(p -> !p.getFileName().toString().equals("BaseQuantityIT.java"))
					.filter(p -> {
						try {
							String body = Files.readString(p);
							return body.contains("CASE unit") || body.contains("CASE m.unit");
						} catch (IOException e) {
							throw new IllegalStateException("Could not read " + p, e);
						}
					})
					.map(Path::toString)
					.toList();

			assertThat(offenders)
					.as("these files convert units by hand instead of calling to_base_qty")
					.isEmpty();
		}
	}

	private void insertShoppingListLine(String unit) {
		admin.update("""
				INSERT INTO shopping_list_lines (tenant_id, ingredient_id, suggested_qty, unit)
				VALUES (?, ?, 10, ?)
				""", tenant, ingredient, unit);
	}
}
