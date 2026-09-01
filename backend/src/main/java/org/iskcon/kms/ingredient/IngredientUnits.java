package org.iskcon.kms.ingredient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one rule about whether a quantity may be said about an ingredient at all (BL-9).
 *
 * <p>{@link Unit} already knows which units convert into which: {@link Unit.Family} is the whole
 * answer, and {@link Unit#baseFactor()} is how the conversion is done. What nothing checked was the
 * step before that — whether the unit a quantity arrives in is in the same family as the unit the
 * ingredient is actually held in. So "3 litres of rice flour" was accepted, written to a
 * purchase-order line, delivered against, and booked into the ledger, where {@code to_base_qty()}
 * turned it into 3,000 of something the store room counts in grams.
 *
 * <p><strong>Same family, not same unit.</strong> A kilo on an order for an ingredient held in
 * grams is ordinary and correct, and issuing and cooking both post their movements in the family's
 * base unit rather than the canonical one ({@code InventoryUnits.baseUnit}). Insisting on the exact
 * unit would refuse every one of those. Insisting on the family refuses only the nonsense.
 *
 * <p><strong>{@code PIECES} is its own family and converts to nothing</strong>, which is not a
 * special case here — it is simply what {@link Unit.Family#COUNT} means, and the same one
 * comparison covers it. Coconuts are not kilograms, no density this application knows of would make
 * them so, and the refusal reads the same either way.
 *
 * <p><strong>Write only.</strong> This refuses a quantity being <em>recorded</em>. Nothing here is
 * consulted on a read: a report over rows written before the rule existed goes on rendering them,
 * because a screen that throws is worse than a screen showing the bad row somebody needs to see in
 * order to correct it.
 */
@Component
public class IngredientUnits {

	private final JdbcTemplate jdbc;

	public IngredientUnits(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Refuses a quantity of {@code ingredientId} measured in {@code given} unless {@code given} is
	 * in the same family as the ingredient's canonical unit.
	 *
	 * <p>The ingredient is read through the tenant-scoped connection, so an id belonging to another
	 * temple is simply not found — the tenant comes from the verified token by way of RLS, never
	 * from anything the caller passed.
	 */
	public void requireSameFamily(UUID ingredientId, Unit given) {
		if (given == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "unit"));
		}
		Ref ref = find(ingredientId);
		if (given.family() == ref.canonical().family()) {
			return;
		}

		// The ingredient's own name and its own unit, which is what makes them safe to say — see
		// ApplicationException's note on `details`. Somebody looking at a twenty-line order needs to
		// be told which line, not that one of them is wrong.
		List<ErrorResponse.FieldError> which = List.of(new ErrorResponse.FieldError(
				ref.name(),
				"%s is measured in %s, and there is no way to turn %s into %s.".formatted(
						ref.name(), ref.canonical().label(), given.label(), ref.canonical().label())));

		throw new ApplicationException(
				ErrorCode.INCOMPATIBLE_UNIT,
				Map.of("ingredientId", ingredientId,
						"given", given.name(),
						"canonicalUnit", ref.canonical().name()),
				which,
				null);
	}

	/** The unit an ingredient is held in. */
	public Unit canonicalUnit(UUID ingredientId) {
		return find(ingredientId).canonical();
	}

	/**
	 * A unit as it arrives from a request body. An unreadable name is a refusal rather than a null:
	 * every unit column carries a CHECK admitting exactly the five names (V74), so letting a bad one
	 * travel would turn a form mistake into a failed insert nobody can read.
	 */
	public static Unit parse(String unit) {
		try {
			return Unit.valueOf(unit.trim());
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "unit", "value", String.valueOf(unit)));
		}
	}

	private Ref find(UUID ingredientId) {
		return jdbc.query("SELECT name, canonical_unit FROM ingredients WHERE id = ?",
						(rs, n) -> new Ref(rs.getString("name"), Unit.valueOf(rs.getString("canonical_unit"))),
						ingredientId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId)));
	}

	private record Ref(String name, Unit canonical) {
	}
}
