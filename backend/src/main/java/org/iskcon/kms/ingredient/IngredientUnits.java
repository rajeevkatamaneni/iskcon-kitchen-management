package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.document.IndianNumbers;
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
 *
 * <h2>The second rule: a counted thing cannot be a fraction (T-423)</h2>
 *
 * <p>The family rule above says a quantity is <em>sayable</em> about an ingredient. It says nothing
 * about whether the number itself is one the thing can be in. Seeding staging produced 7.2 LPG
 * cylinders, 3.6 brooms, 2.4 mops and a return of 1.5 aprons, and every one was recorded without
 * complaint; an apron finished sitting at 88.5 in stock. The unit was known to be {@code PIECES}
 * throughout. What nothing knew is that a piece does not divide, and the schema was never going to
 * say so either — every quantity column is {@code NUMERIC(_, 3)}.
 *
 * <p>So: <strong>anything measured in a {@link Unit.Family#COUNT} unit is a whole number wherever a
 * person or the API can enter it.</strong> {@link #requireWhole} is that rule, and
 * {@link Whole} is it applied to a whole form at once, so a twenty-line order with three fractional
 * lines names all three rather than refusing one at a time.
 *
 * <p><strong>Keyed on the family, never on {@code == PIECES}.</strong> {@link Unit}'s own note says
 * the next counted unit somebody adds — crates, sacks, bundles — is one extra word on its own line,
 * and it must inherit this rule by arriving rather than by anybody remembering to come back here.
 *
 * <p><strong>Entered, not derived.</strong> The rule is about what somebody types. A quantity the
 * application works out for itself is left alone, and there are two kinds of those, both real:
 * cooking a recipe scaled to 12 L genuinely needs 2.4 coconuts, and drawing 5 aprons down from
 * batches that hold 2.5 and 86 genuinely takes 2.5 from the first. Both reach the ledger as
 * movements, which is why {@code StockMovementService.validate} asks this only of the movement
 * kinds whose figure is the one a person typed. See the note there.
 *
 * <p><strong>Rows already written stand.</strong> Nothing here is consulted on a read, exactly as
 * for the family rule, and there is no migration: the 25 fractional goods-receipt lines on staging
 * record what was entered, and that is honest. What they cost is that the fraction can no longer be
 * sent back or billed on its own — the remainder comes off through
 * {@code StockMovementService.compensate}, which reverses a movement past validation on purpose.
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

	// ---- A counted thing cannot be a fraction (T-423) --------------------

	/**
	 * Refuses {@code quantity} unless it is a whole number, when {@code unit} is one the temple
	 * counts one by one. For a single quantity on its own; where a form carries several, use
	 * {@link Whole} so all of them are named at once.
	 *
	 * @param thingName what the refusal calls it — an ingredient's name, a dish's, a recipe's, or the
	 *     description on a one-off order line. It goes in the {@code field} slot of the field error,
	 *     which is how the client puts the message beside the right box.
	 */
	public static void requireWhole(String thingName, BigDecimal quantity, Unit unit) {
		wholeNumbers().check(thingName, quantity, unit).refuseAnyPart();
	}

	/**
	 * The same rule for a path that holds only an ingredient's id: its own unit and its own name are
	 * read from the catalogue first, exactly as {@link #requireSameFamily} does.
	 */
	public void requireWhole(UUID ingredientId, BigDecimal quantity) {
		wholeNumbers(this).check(ingredientId, quantity).refuseAnyPart();
	}

	/** A collector for quantities whose unit the caller already holds. */
	public static Whole wholeNumbers() {
		return new Whole(null);
	}

	/**
	 * A collector that can also look an ingredient's own unit and name up — for the receiving family,
	 * the shopping list and everywhere else the quantity arrives with no unit beside it because the
	 * ingredient's canonical unit is what it is in.
	 */
	public static Whole wholeNumbers(IngredientUnits units) {
		return new Whole(units);
	}

	/**
	 * Every fractional count on one form, collected, so one refusal names all of them.
	 *
	 * <p>A storekeeper keying a twenty-line delivery is not helped by being told about the first bad
	 * line, fixing it, and being told about the second. {@code IngredientIssueService} already builds
	 * its shortfalls this way and for the same reason; this is that shape given to the rule rather
	 * than to one caller.
	 *
	 * <p>Nothing is written by the time {@link #refuseAnyPart()} is reached at any call site here, and
	 * that is the caller's contract rather than this class's: check the whole form, then write.
	 */
	public static final class Whole {

		private final IngredientUnits units;
		private final List<ErrorResponse.FieldError> partial = new ArrayList<>();

		private Whole(IngredientUnits units) {
			this.units = units;
		}

		/**
		 * One quantity in a unit the caller holds. A null quantity or a null unit is passed over —
		 * "there is no figure here" is somebody else's refusal, and answering it with this one would
		 * tell a person to round a box they left empty.
		 */
		public Whole check(String thingName, BigDecimal quantity, Unit unit) {
			if (quantity == null || unit == null || unit.family() != Unit.Family.COUNT || isWhole(quantity)) {
				return this;
			}

			// The whole numbers either side of what was typed, so the next step is a number rather
			// than an instruction. FLOOR and CEILING rather than a rounding: which of the two they
			// meant is theirs to decide, and 7.2 cylinders is as likely to have been 8 as 7.
			BigDecimal below = quantity.setScale(0, RoundingMode.FLOOR);
			BigDecimal above = quantity.setScale(0, RoundingMode.CEILING);

			// The thing's own name and its own unit, which is what makes them safe to say — see
			// ApplicationException's note on `details`.
			partial.add(new ErrorResponse.FieldError(
					thingName,
					"%s is counted in whole %s. Enter %s or %s.".formatted(
							thingName, unit.label(),
							IndianNumbers.group(below, 0, 0), IndianNumbers.group(above, 0, 0))));
			return this;
		}

		/** One quantity in an ingredient's own unit, named by the ingredient's own name. */
		public Whole check(UUID ingredientId, BigDecimal quantity) {
			Ref ref = resolve(ingredientId);
			return check(ref.name(), quantity, ref.canonical());
		}

		/**
		 * One quantity in a unit the caller holds, about an ingredient whose name the caller does not.
		 *
		 * <p><strong>The unit given is the one judged, not the ingredient's own</strong>, and the
		 * difference will matter the day a second counted unit exists. A gift of 5,000 gm of a rice
		 * held in kilos is the same rice measured differently, which is why the family rule allows it;
		 * by the same argument, if crates ever join pieces at twelve to the crate, one and a half
		 * crates is eighteen pieces and is a perfectly whole number of things. Judging the canonical
		 * unit instead would refuse it. Today {@code PIECES} is the only counted unit, so the two
		 * readings agree and nothing distinguishes them — which is exactly why it is worth writing
		 * down now rather than discovering later.
		 */
		public Whole check(UUID ingredientId, BigDecimal quantity, Unit given) {
			// The name is only needed when there is something to say, and the lookup is skipped when
			// there is not — unlike the two-argument form above, which has to read the row to learn
			// the unit at all.
			if (quantity == null || given == null || given.family() != Unit.Family.COUNT || isWhole(quantity)) {
				return this;
			}
			return check(resolve(ingredientId).name(), quantity, given);
		}

		private Ref resolve(UUID ingredientId) {
			if (units == null) {
				throw new IllegalStateException("This collector was not given a way to look an "
						+ "ingredient up — build it with IngredientUnits.wholeNumbers(units)");
			}
			// The read also refuses an ingredient this temple cannot see, which is why the
			// two-argument form does it whether or not the figure turns out to be whole: a refusal
			// that depends on the figure is not a refusal anybody can rely on.
			return units.find(ingredientId);
		}

		/** KMS-400191 naming every line that asked for part of one, or nothing at all. */
		public void refuseAnyPart() {
			if (partial.isEmpty()) {
				return;
			}
			throw new ApplicationException(
					ErrorCode.PART_OF_A_COUNTED_THING,
					Map.of("lines", partial.size()),
					List.copyOf(partial),
					null);
		}
	}

	/**
	 * Whether a figure is a whole number — by <em>value</em>, not by scale.
	 *
	 * <p>{@code BigDecimal.scale()} is not the question and asking it is the trap: a quantity that
	 * has been through JDBC arrives scaled to its {@code NUMERIC(_, 3)} column, so a genuine one
	 * coconut is {@code 1.000} with a scale of 3, and Jackson gives {@code 2.0} a scale of 1. Both
	 * are whole numbers and both must be accepted. {@code stripTrailingZeros} takes the value down to
	 * its own shortest form first, after which a scale above zero is a real fraction.
	 *
	 * <p>The same distinction {@code Unit.label(BigDecimal)} makes with {@code compareTo} rather than
	 * {@code equals}, for the same reason, four files along.
	 */
	private static boolean isWhole(BigDecimal quantity) {
		return quantity.stripTrailingZeros().scale() <= 0;
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
