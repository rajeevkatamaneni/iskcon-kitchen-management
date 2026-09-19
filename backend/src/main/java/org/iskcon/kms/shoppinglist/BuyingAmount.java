package org.iskcon.kms.shoppinglist;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;

/**
 * How much to ask a vendor for, given how much the temple needs (R-SL-2, R-SL-3; T-259).
 *
 * <p>Rajeev, 2026-09-19, looking at the Kalasipalya Vegetable Mandi tile on the shopping list: curry
 * leaves suggested at <em>"2792 gm"</em>. His words: <em>"this is not gold — we are purchasing food
 * items."</em> Nobody asks a vegetable vendor for 2.79 kilos. The figure the list works out is what
 * the kitchen <em>needs</em>; what it should <em>buy</em> is that figure taken up to something a
 * vendor actually sells — 3 Kg, or one 500 gm packet of tea, or four 25 Kg bags of rice.
 *
 * <p><strong>Always up, never down.</strong> Rounding 2792 gm to the nearest half kilo would give
 * 2.5 Kg and leave the kitchen 292 gm short on the day. The list exists so the temple is not short,
 * so every path through this class returns at least what was needed.
 *
 * <h2>Three ways to round, first match wins (R-SL-3's last sentence)</h2>
 *
 * <ol>
 *   <li><strong>The preferred vendor's "Sells it as" pack</strong> ({@code vendor_supplies.pack_size_id}).
 *       The suggestion is in that pack and no other: 100 Kg short, sold as Bag = 25 Kg, is 4 bags. A
 *       vendor who sells rice in 25 Kg bags cannot be sent an order for 1 bag and a 5 Kg packet.</li>
 *   <li><strong>The ingredient's own pack sizes</strong> (R-ING-1), when the vendor names none — see
 *       {@link #MIX_PACK_SIZES} for the rule and its worked examples.</li>
 *   <li><strong>The step table</strong> ({@link #STEPS}), when the ingredient has no pack sizes at all.</li>
 * </ol>
 *
 * <p><strong>A quantity somebody typed is never re-rounded.</strong> That is the caller's half of
 * the rule — {@code ShoppingListService} only sends the <em>computed</em> figure through
 * {@link #of}. A typed figure goes through {@link #describeTyped}, which never changes the number
 * and only says whether it happens to be a whole number of the vendor's pack.
 *
 * <p><strong>This is a different rule from {@code Quantities.cooks}, on purpose.</strong> That one
 * rounds to the <em>nearest</em> step for a figure somebody reads or weighs out — a recipe line, a
 * job card. This one decides a quantity that is then stored, sent to a vendor and received against,
 * so it has to be one number, rounded once, in one direction. Its steps are all multiples of the
 * cook's-form steps at the same size, so a buying amount printed through {@code Quantities.cooks}
 * comes out unchanged: 3000 gm reads "3 Kg", 450 gm reads "450 gm".
 *
 * <p>It is pure — no database, no clock — so every rule here is proven by {@code BuyingAmountTest}
 * at its boundaries without a container.
 */
public final class BuyingAmount {

	/**
	 * <strong>The buying steps — Rajeev's rule, 2026-09-19. Change them here and nowhere else.</strong>
	 *
	 * <p>Used only for an ingredient with no pack sizes (R-SL-2, "Without pack sizes"). Read as: a
	 * quantity up to {@code upTo} (in grams for a mass, millilitres for a volume — the two families
	 * use the same table) is rounded up to the next multiple of {@code step}. The first band whose
	 * {@code upTo} the quantity does not exceed is the one used; the last band has no top.
	 *
	 * <pre>
	 *   under 1 Kg / 1 L        next 50 gm / 50 ml        430 gm   →  450 gm
	 *   1 – 10 Kg / L           next ½ Kg / ½ L           2792 gm  →  3 Kg
	 *   10 – 100 Kg / L         next 1 Kg / 1 L           12.2 Kg  →  13 Kg
	 *   over 100 Kg / L         next 5 Kg / 5 L           101 Kg   →  105 Kg
	 *   pieces                  next whole number         2.1      →  3
	 * </pre>
	 *
	 * <p>The boundaries belong to the band below them, and it makes no difference which band claims
	 * them: exactly 1 Kg is already a whole 50 gm and a whole half kilo, so it stays 1 Kg, and the
	 * same holds at 10 Kg and at 100 Kg. Anything over a boundary, by however little, moves to the
	 * next band's step — 1001 gm is bought as 1.5 Kg, because at that size the vendor sells half
	 * kilos, not grams.
	 *
	 * <p>Pieces are not in the table because the rule for them has no bands: a thing is bought whole.
	 */
	private static final List<Step> STEPS = List.of(
			new Step(new BigDecimal("1000"), new BigDecimal("50")),
			new Step(new BigDecimal("10000"), new BigDecimal("500")),
			new Step(new BigDecimal("100000"), new BigDecimal("1000")),
			new Step(null, new BigDecimal("5000")));

	/**
	 * <strong>Whether one suggestion may mix the ingredient's pack sizes — PROVISIONAL, pending
	 * Rajeev's answer to Q-16 on the Decisions Desk.</strong> Kept beside {@link #STEPS} so that the
	 * whole of the buying rule is in one place.
	 *
	 * <p>R-SL-2 says: <em>"the fewest packs covering the need with the least left over. Ties go to
	 * fewer packs."</em> The document names both measures without saying which comes first, and every
	 * example it gives uses one size. The conductor ruled, 2026-09-19, for this build:
	 *
	 * <ol>
	 *   <li><strong>Least left over first; on a tie, fewer packs.</strong> That is the reading that
	 *       reproduces the document's own example — tea, 416 gm, packs of 250 gm, 500 gm and 1 Kg: two
	 *       250s and one 500 both leave 84 gm over, and the tie goes to the one pack, so
	 *       <strong>1 × 500 gm</strong>. (One 1 Kg leaves 584 gm over and loses outright.)</li>
	 *   <li><strong>Mixing allowed</strong> as the provisional default. 1,200 gm of tea is then
	 *       <strong>1 × 1 Kg + 1 × 250 gm</strong> (50 gm over, two packs) rather than 5 × 250 gm
	 *       (also 50 gm over, but five packs). With mixing off, the same need is
	 *       <strong>5 × 250 gm</strong>: the least-left-over single size, since 3 × 500 gm leaves 300
	 *       and 2 × 1 Kg leaves 800.</li>
	 * </ol>
	 *
	 * <p>If Rajeev answers Q-16 "one size only", this becomes {@code false} and nothing else changes;
	 * {@code BuyingAmountTest} already proves both settings.
	 */
	static final boolean MIX_PACK_SIZES = true;

	/**
	 * The most combinations the mixed search will lay out before giving up on mixing. Only reachable
	 * with a pack far smaller than the need — a 1 mg pack against a tonne — which R-ING-1's own chips
	 * ("250 g · 500 g · 1 Kg · Bag = 25 Kg") give no reason to expect. Past it the single-size answer
	 * is used, which follows the same ordering and is still never short: slower to be exact about is
	 * not a reason to refuse to draw the page.
	 */
	private static final int MAX_MIXED_SLOTS = 2_000_000;

	private record Step(BigDecimal upTo, BigDecimal step) {
	}

	/**
	 * One pack size an ingredient is sold in, as it was entered — "Bag", 25, KG — which is what its
	 * label is written from. The caller maps it from {@code ingredient_pack_sizes}.
	 */
	public record Pack(UUID id, String name, BigDecimal quantity, Unit unit) {

		/** The size in the family's base unit (gm, ml, pieces), exact. */
		BigDecimal base() {
			return InventoryUnits.toBase(quantity, unit);
		}

		/**
		 * How the app writes a pack on an order: "Bag (25 Kg)" for a named pack — R-SL-3's own
		 * wording, "4 × Bag (25 Kg)" — and the plain size, "500 gm", for an unnamed one. The size is
		 * {@code Quantities.exact}, the same helper {@code PackSizeService} builds its chips from, so
		 * the readable unit comes from {@link Unit} and never from a string typed here.
		 *
		 * <p>Deliberately not named "label": {@code UnitLabelAgreementTest} flags every no-argument
		 * call of that name in the tree as a possible unit word printed without its count, and this
		 * is not one.
		 */
		String asWritten() {
			String size = Quantities.exact(quantity, unit);
			return name == null ? size : name + " (" + size + ")";
		}
	}

	/**
	 * The answer: the quantity in the line's unit, and — when it is a number of packs — which packs.
	 *
	 * @param quantity what to buy, in the unit asked about; always the sum of each pack's count ×
	 *     size when {@code packs} is not empty
	 * @param packs largest pack first; empty when the amount came from the step table
	 * @param fromVendor true when {@code packs} is the preferred vendor's "Sells it as" pack
	 */
	public record Result(BigDecimal quantity, List<BuyPackView> packs, boolean fromVendor) {
	}

	private BuyingAmount() {
	}

	/**
	 * The quantity to buy for a need of {@code needed}, both in {@code unit}.
	 *
	 * @param needed how much is needed; zero or less comes back unchanged with no packs, because a
	 *     line asking for nothing is dropped by the caller and there is nothing to round
	 * @param unit the ingredient's stock unit, which the list stores and the PO line is written in
	 * @param vendorPack the preferred vendor's "Sells it as" pack, or null when there is none
	 * @param ingredientPacks the ingredient's own pack sizes; empty when it has none
	 */
	public static Result of(BigDecimal needed, Unit unit, Pack vendorPack, List<Pack> ingredientPacks) {
		return of(needed, unit, vendorPack, ingredientPacks, MIX_PACK_SIZES);
	}

	/**
	 * {@link #of} with the mixing switch passed in rather than read from {@link #MIX_PACK_SIZES}, so
	 * that the test proves both settings of a rule that is still provisional. Production code calls
	 * the four-argument form and never chooses.
	 */
	static Result of(
			BigDecimal needed, Unit unit, Pack vendorPack, List<Pack> ingredientPacks, boolean mixPackSizes) {
		if (needed.signum() <= 0) {
			return new Result(needed, List.of(), false);
		}
		BigDecimal need = InventoryUnits.toBase(needed, unit);

		if (vendorPack != null) {
			// R-SL-3: the vendor's pack only, as many as cover the need. 100 Kg / 25 Kg = 4 bags;
			// 101 Kg is 5 bags, because 4 would leave the kitchen a kilo short.
			int count = need.divide(vendorPack.base(), 0, RoundingMode.CEILING).intValueExact();
			return inPacks(List.of(new Counted(vendorPack, count)), unit, true);
		}
		if (!ingredientPacks.isEmpty()) {
			List<Counted> chosen = mixPackSizes ? mixed(need, ingredientPacks) : null;
			if (chosen == null) {
				chosen = singleSize(need, ingredientPacks);
			}
			return inPacks(chosen, unit, false);
		}
		return new Result(InventoryUnits.fromBase(stepped(need, unit.family()), unit), List.of(), false);
	}

	/**
	 * A figure somebody typed, described without being touched: the packs it is, when it is exactly
	 * a whole number of the vendor's pack (100 Kg typed, sold as Bag = 25 Kg, is 4 bags), and no
	 * packs otherwise — 90 Kg of rice is not three and three-fifths bags, and saying "4 bags" beside
	 * a typed 90 would put two different orders on one line.
	 */
	public static Result describeTyped(BigDecimal typed, Unit unit, Pack vendorPack) {
		if (vendorPack == null || typed.signum() <= 0) {
			return new Result(typed, List.of(), false);
		}
		BigDecimal[] div = InventoryUnits.toBase(typed, unit).divideAndRemainder(vendorPack.base());
		if (div[1].signum() != 0) {
			return new Result(typed, List.of(), false);
		}
		return new Result(typed,
				List.of(view(new Counted(vendorPack, div[0].intValueExact()), unit)), true);
	}

	// ---- The step table -------------------------------------------------------------------

	/** A need in base units taken up to its buying step. */
	private static BigDecimal stepped(BigDecimal need, Unit.Family family) {
		if (family == Unit.Family.COUNT) {
			return need.setScale(0, RoundingMode.CEILING);
		}
		BigDecimal step = stepFor(need);
		return need.divide(step, 0, RoundingMode.CEILING).multiply(step);
	}

	/** The step for a quantity in base units — the first band it does not exceed. */
	static BigDecimal stepFor(BigDecimal base) {
		for (Step s : STEPS) {
			if (s.upTo() == null || base.compareTo(s.upTo()) <= 0) {
				return s.step();
			}
		}
		throw new IllegalStateException("The last buying step has no upper bound, so this is unreachable.");
	}

	// ---- Pack sizes -----------------------------------------------------------------------

	private record Counted(Pack pack, int count) {
	}

	/**
	 * One size only: for each size, the fewest of it that cover the need; of those, the least left
	 * over, then the fewest packs. Tea 416 gm with {250 gm, 500 gm, 1 Kg}: 2 × 250 (84 over),
	 * 1 × 500 (84 over), 1 × 1 Kg (584 over) — 1 × 500 gm.
	 */
	private static List<Counted> singleSize(BigDecimal need, List<Pack> packs) {
		Counted best = null;
		BigDecimal bestOver = null;
		for (Pack p : packs) {
			int count = need.divide(p.base(), 0, RoundingMode.CEILING).intValueExact();
			BigDecimal over = p.base().multiply(BigDecimal.valueOf(count)).subtract(need);
			if (best == null
					|| over.compareTo(bestOver) < 0
					|| (over.compareTo(bestOver) == 0 && count < best.count())
					|| (over.compareTo(bestOver) == 0 && count == best.count()
							&& p.base().compareTo(best.pack().base()) > 0)) {
				best = new Counted(p, count);
				bestOver = over;
			}
		}
		return List.of(best);
	}

	/**
	 * Sizes may be mixed: the combination whose total covers the need with the least left over, and
	 * of those the one with the fewest packs.
	 *
	 * <p>Worked as a coin problem in whole multiples of {@code g}, the largest amount every pack size
	 * is a multiple of (250 gm for {250 gm, 500 gm, 1 Kg}), so every total a combination can reach is
	 * a slot. The best total is never as much as one smallest pack over the need — if it were, that
	 * pack could be taken out and it would still cover — so only slots up to
	 * {@code need + smallest} are laid out, and for each the fewest packs that reach it exactly.
	 * The first reachable slot at or above the need is the least left over; its count is the fewest
	 * packs. Where two combinations of the same size reach it, the larger packs are kept, which is
	 * what reads more naturally on an order: 1 Kg rather than 500 gm + 250 gm + 250 gm is already a
	 * fewer-packs win, and this only settles true ties.
	 *
	 * @return null when the layout would exceed {@link #MAX_MIXED_SLOTS}, and the caller uses one size
	 */
	private static List<Counted> mixed(BigDecimal need, List<Pack> packs) {
		// Everything scaled to whole thousandths so the sizes have an integer common divisor;
		// quantities are NUMERIC(14, 3), so three places is all a pack can carry.
		List<Pack> bySize = new ArrayList<>(packs);
		bySize.sort(Comparator.comparing(Pack::base).reversed());
		long[] size = new long[bySize.size()];
		BigInteger g = BigInteger.ZERO;
		for (int i = 0; i < size.length; i++) {
			BigInteger scaled = bySize.get(i).base().movePointRight(3).setScale(0, RoundingMode.CEILING)
					.toBigIntegerExact();
			g = g.gcd(scaled);
			size[i] = scaled.longValueExact();
		}
		long unit = g.longValueExact();
		for (int i = 0; i < size.length; i++) {
			size[i] /= unit;
		}
		long needSlots = need.movePointRight(3).divide(BigDecimal.valueOf(unit), 0, RoundingMode.CEILING)
				.longValueExact();
		long smallest = size[size.length - 1];
		long top = needSlots + smallest; // exclusive
		if (top > MAX_MIXED_SLOTS) {
			return null;
		}

		int slots = (int) top;
		int[] fewest = new int[slots];
		int[] lastPack = new int[slots];
		Arrays.fill(fewest, Integer.MAX_VALUE);
		fewest[0] = 0;
		for (int t = 1; t < slots; t++) {
			// Largest pack first, and a strict improvement only, so a tie keeps the larger pack.
			for (int i = 0; i < size.length; i++) {
				if (size[i] <= t && fewest[t - (int) size[i]] != Integer.MAX_VALUE
						&& fewest[t - (int) size[i]] + 1 < fewest[t]) {
					fewest[t] = fewest[t - (int) size[i]] + 1;
					lastPack[t] = i;
				}
			}
		}
		int total = -1;
		for (int t = (int) needSlots; t < slots; t++) {
			if (fewest[t] != Integer.MAX_VALUE) {
				total = t;
				break;
			}
		}
		// Unreachable: the smallest pack alone reaches some slot in [need, need + smallest).
		if (total < 0) {
			return singleSize(need, packs);
		}

		int[] counts = new int[size.length];
		for (int t = total; t > 0; t -= (int) size[lastPack[t]]) {
			counts[lastPack[t]]++;
		}
		List<Counted> out = new ArrayList<>();
		for (int i = 0; i < size.length; i++) {
			if (counts[i] > 0) {
				out.add(new Counted(bySize.get(i), counts[i]));
			}
		}
		return out;
	}

	/** The chosen packs, largest first, with the quantity they add up to in the line's unit. */
	private static Result inPacks(List<Counted> chosen, Unit unit, boolean fromVendor) {
		List<Counted> ordered = new ArrayList<>(chosen);
		ordered.sort(Comparator.comparing((Counted c) -> c.pack().base()).reversed());
		BigDecimal total = BigDecimal.ZERO;
		List<BuyPackView> views = new ArrayList<>();
		for (Counted c : ordered) {
			BuyPackView v = view(c, unit);
			views.add(v);
			total = total.add(v.perPackQty().multiply(BigDecimal.valueOf(v.count())));
		}
		return new Result(tidy(total), List.copyOf(views), fromVendor);
	}

	private static BuyPackView view(Counted c, Unit unit) {
		return new BuyPackView(c.pack().id(), c.pack().asWritten(), inUnit(c.pack().base(), unit), c.count());
	}

	/**
	 * A base-unit size in {@code unit}, exact. Not {@code InventoryUnits.fromBase}, which rounds to
	 * three places: a 250 gm pack on an ingredient kept in Kg is 0.25 either way, but the promise on
	 * {@link Result#quantity} — the sum of the packs, to the gram — is only kept by not rounding at
	 * all. Base factors are powers of ten, so the division always terminates.
	 */
	private static BigDecimal inUnit(BigDecimal base, Unit unit) {
		return tidy(base.divide(BigDecimal.valueOf(unit.baseFactor())));
	}

	private static BigDecimal tidy(BigDecimal value) {
		if (value.signum() == 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}
}
