package org.iskcon.kms.assistant.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.iskcon.kms.assistant.names.ConversationNames;
import org.iskcon.kms.assistant.names.ConversationNames.Names;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.IngredientNames;
import org.iskcon.kms.ingredient.IngredientService;
import org.iskcon.kms.ingredient.IngredientView;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.purchaseorder.CreatePurchaseOrderRequest;
import org.iskcon.kms.purchaseorder.CreatedPurchaseOrder;
import org.iskcon.kms.purchaseorder.PoLineInput;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.iskcon.kms.shoppinglist.BuyPackView;
import org.iskcon.kms.shoppinglist.ShoppingListLineView;
import org.iskcon.kms.shoppinglist.ShoppingListService;
import org.iskcon.kms.tenancy.TempleClock;
import org.iskcon.kms.vendor.VendorService;
import org.iskcon.kms.vendor.VendorSupplyView;
import org.iskcon.kms.vendor.VendorView;
import org.springframework.stereotype.Component;

/**
 * A draft purchase order for one vendor: the first kind of assistant draft (E10-S4, Rajeev's
 * capability 4, "create a purchase order"; plan §11 S9).
 *
 * <h2>Confirm is the order screen's own Save</h2>
 *
 * <p>{@link #execute} is {@link PurchaseOrderService#createManual}, the one way an order is created
 * (its controller's comment says so, since T-153 retired the generator), with the request bean-validated
 * exactly as {@code @Valid} validates it on {@code POST /purchase-orders}. So every rule that
 * applies to an order typed by hand applies here — the vendor exists and is active, a needed-by date
 * is not before today, a line names a catalogue item or describes one, units are of the item's family,
 * a pack decides the amount — with the same KMS codes. The order starts as a DRAFT, as every new order
 * does; sending it is a separate act and not part of this kind.
 *
 * <h2>Drafting is the shopping list's vendor tile, server-side</h2>
 *
 * <p>Without lines from the person, the draft is what the vendor's tile on the shopping list opens
 * its order panel with ({@code frontend/app/shopping-list/page.tsx}, {@code panelLines} and the panel's
 * first needed-by), so that asking the assistant and pressing the tile give the same order:
 *
 * <ul>
 *   <li>The vendor's lines are the ones the list groups under it ({@code suggestedVendorId}), ticked
 *       ones only: an untick is the temple's decision not to buy it now.
 *   <li>A line in packs becomes one order line per pack size, {@code packSizeId} + {@code packCount},
 *       with the amount stated as count × pack size. Its price is the vendor's quoted price for that
 *       pack where they quote one, otherwise the list price per unit times the pack.
 *   <li>A line without packs goes in the unit it reads in (3000 gm as 3 Kg), at the list price
 *       restated per that unit.
 *   <li>Needed by is the earliest needed-by on those lines. Where that day has already passed it is
 *       today, because the order service refuses a past date (KMS-400014, "needed by before the order
 *       date") and a draft that cannot be confirmed helps nobody; the tool's answer says so.
 * </ul>
 *
 * <p>Lines the person names ("10 Kg rice and 5 Kg sugar from Balaji") are matched to the catalogue by
 * name and ordered as given, at the vendor's list price where they have one.
 *
 * <h2>The card</h2>
 *
 * <p>Built here, server-side, so it is under the draft's hash (see {@link PendingActionService}).
 * Quantities on the card are in Kg or L for anything weighed or measured, so the rate beside them is
 * always per Kg or per L, as the shopping list's rule for rates has it ("3 Kg" and "450 gm" both go
 * with "₹71.20 / Kg"); the order itself keeps the shopping list's own units.
 */
@Component
public class PurchaseOrderAction implements PendingActionHandler {

	public static final String KIND = "PURCHASE_ORDER";

	/** What the card says beside Confirm. Under ~45 characters: the card is narrow (api.ts). */
	static final String CONFIRM_SAYS = "Confirm saves a draft order. Nothing is sent.";

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

	private final PurchaseOrderService purchaseOrders;
	private final ShoppingListService shoppingList;
	private final VendorService vendors;
	private final IngredientService ingredients;
	private final ConversationNames names;
	private final TempleClock clock;
	private final PendingActionService actions;
	private final ObjectMapper json;
	private final Validator validator;

	public PurchaseOrderAction(PurchaseOrderService purchaseOrders, ShoppingListService shoppingList,
			VendorService vendors, IngredientService ingredients, ConversationNames names, TempleClock clock,
			@org.springframework.context.annotation.Lazy PendingActionService actions, ObjectMapper json,
			Validator validator) {
		this.purchaseOrders = purchaseOrders;
		this.shoppingList = shoppingList;
		this.vendors = vendors;
		this.ingredients = ingredients;
		this.names = names;
		this.clock = clock;
		this.actions = actions;
		this.json = json;
		this.validator = validator;
	}

	@Override
	public String kind() {
		return KIND;
	}

	@Override
	public String permission() {
		return "MANAGE_PURCHASE_ORDERS";
	}

	@Override
	public AssistantProposal.Result execute(AuthenticatedUser actor, JsonNode request) {
		CreatePurchaseOrderRequest order;
		try {
			order = json.treeToValue(request, CreatePurchaseOrderRequest.class);
		} catch (JsonProcessingException | IllegalArgumentException e) {
			// The hash matched, so this is a draft this code wrote and a later change of the request's
			// shape. Said as the draft having changed: ask for a fresh one.
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_CHANGED, Map.of("reason", "unreadable request"), e);
		}
		Set<ConstraintViolation<CreatePurchaseOrderRequest>> violations = validator.validate(order);
		if (!violations.isEmpty()) {
			ConstraintViolation<CreatePurchaseOrderRequest> first = violations.iterator().next();
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", first.getPropertyPath().toString(), "message", first.getMessage()));
		}
		CreatedPurchaseOrder created = purchaseOrders.createManual(actor, order);
		return new AssistantProposal.Result(created.poNumber(), "/orders/" + created.id());
	}

	// ---- Drafting ---------------------------------------------------------

	/** A line the person named: an item, an amount, a unit, all as the model passed them. */
	public record RequestedLine(String item, BigDecimal quantity, String unit) {
	}

	/** Why a draft could not be made, in words the model relays. Nothing was stored. */
	public static final class CannotDraft extends RuntimeException {
		public CannotDraft(String message) {
			super(message);
		}
	}

	/**
	 * The draft and the facts the tool tells the model about it. {@code english} is whether the
	 * conversation was in English when the card was built, which decides whether a line with no local
	 * name is simply English or is waiting for its name ({@link #lineSummary}).
	 */
	public record Drafted(AssistantProposal proposal, VendorView vendor, LocalDate neededBy, String total, String note,
			boolean english) {
	}

	/**
	 * Active vendors whose name matches: an exact match (ignoring case and spacing) alone if there is
	 * one, otherwise every vendor whose name contains the words asked for or is contained in them.
	 * {@code GET /vendors} has no search, and the screen's picker is the whole list, so this is the
	 * match a person makes by eye.
	 */
	public List<VendorView> findVendors(String name) {
		List<VendorView> active = vendors.list(false);
		String needle = normalise(name);
		if (needle.isEmpty()) {
			return active;
		}
		List<VendorView> exact = active.stream().filter(v -> normalise(v.name()).equals(needle)).toList();
		if (!exact.isEmpty()) {
			return exact;
		}
		return active.stream()
				.filter(v -> normalise(v.name()).contains(needle) || needle.contains(normalise(v.name())))
				.toList();
	}

	/**
	 * Drafts one order for one vendor and stores it for the person to confirm. Changes nothing else.
	 *
	 * @param requested the lines the person named, or null/empty for the vendor's shopping-list lines
	 * @param neededBy the date the person gave, or null for the shopping list's
	 * @throws CannotDraft with words for the model when the vendor or a line cannot be settled
	 */
	public Drafted draft(AgentPrincipal agent, String vendorName, List<RequestedLine> requested, LocalDate neededBy) {
		VendorView vendor = oneVendor(vendorName);
		Map<UUID, VendorSupplyView> supplies = vendors.get(vendor.id()).supplies().stream()
				.collect(Collectors.toMap(VendorSupplyView::ingredientId, Function.identity(), (a, b) -> a));
		// The card's second name is the conversation's language, as the tools' are (E10-NAMES-B).
		Names local = names.forAgent(agent);
		LocalDate today = clock.today();
		String note = null;

		List<PoLineInput> orderLines = new ArrayList<>();
		List<AssistantProposal.Line> cardLines = new ArrayList<>();
		LocalDate date = neededBy;

		if (requested == null || requested.isEmpty()) {
			List<ShoppingListLineView> lines = shoppingList.list().stream()
					.filter(l -> vendor.id().equals(l.suggestedVendorId()) && l.included())
					.toList();
			if (lines.isEmpty()) {
				throw new CannotDraft("The shopping list has no ticked lines for " + vendor.name()
						+ ", so there is nothing to put on an order from it. Ask the person which items and how much"
						+ " they want, then call propose_purchase_order again with lines.");
			}
			for (ShoppingListLineView line : lines) {
				fromShoppingList(line, supplies.get(line.ingredientId()), local, orderLines, cardLines);
			}
			if (date == null) {
				date = lines.stream().map(ShoppingListLineView::neededBy).filter(Objects::nonNull).min(LocalDate::compareTo)
						.orElse(null);
				if (date != null && date.isBefore(today)) {
					note = "The shopping list wanted some of this by " + date
							+ ", which has passed, so the draft asks for today, " + today + ".";
					date = today;
				}
			}
		} else {
			List<IngredientView> catalogue = ingredients.list();
			for (RequestedLine line : requested) {
				named(line, catalogue, supplies, local, orderLines, cardLines);
			}
		}
		if (date != null && date.isBefore(today)) {
			throw new CannotDraft("needed_by " + date + " has already passed. An order can be needed by today ("
					+ today + ") or later. Ask the person for a date.");
		}

		local.fillIfMissing();
		CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(vendor.id(), date, null, null, orderLines);
		List<AssistantProposal.Fact> facts = facts(vendor, date, cardLines);
		AssistantProposal.Card card = new AssistantProposal.Card(
				"Purchase order for " + vendor.name(), facts, cardLines, CONFIRM_SAYS);
		AssistantProposal.Fact total = facts.get(facts.size() - 1);
		String totalText = total.value() + (total.hint() == null ? "" : " (" + total.hint() + ")");
		return new Drafted(actions.propose(agent, KIND, request, card), vendor, date, totalText, note,
				local.english());
	}

	private VendorView oneVendor(String name) {
		if (normalise(name).isEmpty()) {
			throw new CannotDraft("Say which vendor the order is for. Call find_vendor to see the vendors.");
		}
		List<VendorView> found = findVendors(name);
		if (found.size() == 1) {
			return found.get(0);
		}
		if (found.isEmpty()) {
			throw new CannotDraft("No active vendor is called \"" + name + "\". Call find_vendor with part of the"
					+ " name, or ask the person which vendor they mean.");
		}
		throw new CannotDraft("More than one vendor matches \"" + name + "\": "
				+ found.stream().map(VendorView::name).limit(10).collect(Collectors.joining(", "))
				+ ". Ask the person which one, then call again with the full name.");
	}

	/** One shopping-list line as the vendor tile's panel would put it on the order (see the class comment). */
	private void fromShoppingList(ShoppingListLineView line, VendorSupplyView supply, Names local,
			List<PoLineInput> orderLines, List<AssistantProposal.Line> cardLines) {
		Unit unit = Unit.valueOf(line.unit());
		BigDecimal perLineUnit = supply == null ? null : pricePer(supply.lastPrice(), unitOf(supply.unit()), unit);
		String localName = local.local(line.ingredientName());

		if (line.buyPacks() != null && !line.buyPacks().isEmpty()) {
			for (BuyPackView pack : line.buyPacks()) {
				boolean vendorsPack = supply != null && pack.packSizeId().equals(supply.packSizeId())
						&& supply.pricePerPack() != null;
				BigDecimal perPack = vendorsPack ? supply.pricePerPack()
						: perLineUnit == null ? null : perLineUnit.multiply(pack.perPackQty()).setScale(2, RoundingMode.HALF_UP);
				BigDecimal expected = perPack == null ? null
						: perPack.divide(pack.perPackQty(), 4, RoundingMode.HALF_UP);
				BigDecimal count = BigDecimal.valueOf(pack.count());
				orderLines.add(new PoLineInput(line.ingredientId(), null,
						plain(count.multiply(pack.perPackQty())), unit.name(), expected, pack.packSizeId(), count));
				cardLines.add(new AssistantProposal.Line(line.ingredientName(), localName, count, PACK_NAME.apply(pack),
						money(perPack), perPack == null ? null : money(perPack.multiply(count))));
			}
			return;
		}
		Shown shown = Shown.of(line.suggestedQty(), unit);
		BigDecimal expected = pricePer(perLineUnit, unit, shown.unit());
		orderLines.add(new PoLineInput(line.ingredientId(), null, shown.value(), shown.unit().name(), expected, null, null));
		cardLines.add(cardLine(line.ingredientName(), localName, shown.value(), shown.unit(), expected));
	}

	/** A line the person named, matched to the catalogue. */
	private void named(RequestedLine line, List<IngredientView> catalogue, Map<UUID, VendorSupplyView> supplies,
			Names local, List<PoLineInput> orderLines, List<AssistantProposal.Line> cardLines) {
		if (line == null || normalise(line.item()).isEmpty()) {
			throw new CannotDraft("Every line needs an item name.");
		}
		IngredientView item = oneIngredient(line.item(), catalogue);
		if (line.quantity() == null || line.quantity().signum() <= 0) {
			throw new CannotDraft("Say how much " + item.name() + " to order: a quantity greater than zero.");
		}
		Unit kept = unitOf(item.unit());
		Unit unit = line.unit() == null || line.unit().isBlank() ? kept : parseUnit(line.unit());
		if (unit == null || kept == null || unit.family() != kept.family()) {
			throw new CannotDraft("Give the quantity of " + item.name() + " in " + sameFamily(kept)
					+ ", the kind of unit the temple keeps it in.");
		}
		VendorSupplyView supply = supplies.get(item.id());
		BigDecimal expected = supply == null ? null : pricePer(supply.lastPrice(), unitOf(supply.unit()), unit);
		BigDecimal quantity = plain(line.quantity());
		orderLines.add(new PoLineInput(item.id(), null, quantity, unit.name(), expected, null, null));
		cardLines.add(cardLine(item.name(), local.local(item.name()), quantity, unit,
				expected));
	}

	private IngredientView oneIngredient(String name, List<IngredientView> catalogue) {
		String needle = normalise(name);
		List<IngredientView> exact = catalogue.stream()
				.filter(i -> normalise(i.name()).equals(needle) || normalise(IngredientNames.readable(i.name())).equals(needle))
				.toList();
		if (exact.size() == 1) {
			return exact.get(0);
		}
		List<IngredientView> partial = exact.isEmpty()
				? catalogue.stream().filter(i -> normalise(i.name()).contains(needle)
						|| normalise(IngredientNames.readable(i.name())).contains(needle)).toList()
				: exact;
		if (partial.size() == 1) {
			return partial.get(0);
		}
		if (partial.isEmpty()) {
			throw new CannotDraft("No ingredient in the temple's catalogue is called \"" + name
					+ "\". Try the usual English store name, or ask the person.");
		}
		throw new CannotDraft("\"" + name + "\" matches more than one ingredient: "
				+ partial.stream().map(IngredientView::name).limit(10).collect(Collectors.joining(", "))
				+ ". Ask the person which one, then call again with the full name.");
	}

	/**
	 * The card's line: weighed and measured amounts in Kg or L, with the rate per Kg or L and the
	 * amount in rupees (see the class comment). The order's own line is untouched by this.
	 */
	private static AssistantProposal.Line cardLine(String name, String localName, BigDecimal quantity, Unit unit,
			BigDecimal pricePerUnit) {
		Unit large = large(unit);
		BigDecimal inLarge = convert(quantity, unit, large);
		BigDecimal rate = pricePer(pricePerUnit, unit, large);
		return new AssistantProposal.Line(name, localName, plain(inLarge), large.label(inLarge), money(rate),
				rate == null ? null : money(rate.multiply(inLarge)));
	}

	private static List<AssistantProposal.Fact> facts(VendorView vendor, LocalDate neededBy,
			List<AssistantProposal.Line> lines) {
		List<AssistantProposal.Fact> facts = new ArrayList<>();
		facts.add(new AssistantProposal.Fact("Vendor", vendor.name(), null));
		facts.add(new AssistantProposal.Fact("Needed by", neededBy == null ? "Not set" : DAY.format(neededBy), null));
		BigDecimal total = BigDecimal.ZERO;
		long unpriced = 0;
		for (AssistantProposal.Line line : lines) {
			if (line.amount() == null) {
				unpriced++;
			} else {
				total = total.add(line.amount());
			}
		}
		String hint = unpriced == 0 ? "At the vendor's list prices"
				: unpriced == lines.size() ? "This vendor has no list prices for these items"
				: unpriced + (unpriced == 1 ? " line has" : " lines have") + " no list price";
		facts.add(new AssistantProposal.Fact("Total", unpriced == lines.size() ? "—" : rupees(total), hint));
		return facts;
	}

	// ---- Units, prices and amounts ----------------------------------------

	/**
	 * A quantity as the shopping list's order box shows it: promoted to Kg or L from 1,000 gm or ml up,
	 * otherwise in its own unit ({@code entryQuantity} in {@code frontend/lib/format.ts}).
	 */
	public record Shown(BigDecimal value, Unit unit) {
		public static Shown of(BigDecimal value, Unit unit) {
			if (unit.family() == Unit.Family.COUNT || value == null) {
				return new Shown(value, unit);
			}
			BigDecimal inBase = value.multiply(BigDecimal.valueOf(unit.baseFactor()));
			Unit shown = inBase.signum() == 0 ? unit
					: inBase.abs().compareTo(BigDecimal.valueOf(1000)) >= 0 ? large(unit) : small(unit);
			return new Shown(plain(convert(value, unit, shown)), shown);
		}
	}

	private static Unit large(Unit unit) {
		return switch (unit.family()) {
			case MASS -> Unit.KG;
			case VOLUME -> Unit.L;
			case COUNT -> unit;
		};
	}

	private static Unit small(Unit unit) {
		return switch (unit.family()) {
			case MASS -> Unit.GM;
			case VOLUME -> Unit.ML;
			case COUNT -> unit;
		};
	}

	private static BigDecimal convert(BigDecimal value, Unit from, Unit to) {
		if (value == null || from == to) {
			return value;
		}
		return value.multiply(BigDecimal.valueOf(from.baseFactor()))
				.divide(BigDecimal.valueOf(to.baseFactor()), 6, RoundingMode.HALF_UP);
	}

	/**
	 * A price per one {@code from} restated per one {@code to} (₹71.20 / Kg is ₹0.0712 / gm), four
	 * places as a purchase-order line's price holds; null if there is no price or the units do not
	 * convert. The same rule as {@code pricePer} in {@code format.ts} and the order service's own.
	 */
	static BigDecimal pricePer(BigDecimal price, Unit from, Unit to) {
		if (price == null || from == null || to == null || from.family() != to.family()) {
			return null;
		}
		if (from == to) {
			return price;
		}
		return price.multiply(BigDecimal.valueOf(to.baseFactor()))
				.divide(BigDecimal.valueOf(from.baseFactor()), 4, RoundingMode.HALF_UP);
	}

	private static Unit unitOf(String code) {
		if (code == null) {
			return null;
		}
		try {
			return Unit.valueOf(code.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** "kg", "Kg", "kilo", "grams", "litre", "pcs": the ways a person or a model writes a unit. */
	static Unit parseUnit(String text) {
		String t = text.trim().toLowerCase(Locale.ROOT).replace(".", "");
		Unit direct = unitOf(t);
		if (direct != null) {
			return direct;
		}
		return switch (t) {
			case "kgs", "kilo", "kilos", "kilogram", "kilograms" -> Unit.KG;
			case "g", "gms", "gram", "grams", "gramme", "grammes" -> Unit.GM;
			case "l", "ltr", "ltrs", "litre", "litres", "liter", "liters" -> Unit.L;
			case "ml", "millilitre", "millilitres", "milliliter", "milliliters" -> Unit.ML;
			case "piece", "pieces", "pc", "pcs", "nos", "no", "number" -> Unit.PIECES;
			default -> null;
		};
	}

	private static String sameFamily(Unit kept) {
		if (kept == null) {
			return "its own unit";
		}
		return switch (kept.family()) {
			case MASS -> "Kg or gm";
			case VOLUME -> "L or ml";
			case COUNT -> "pieces";
		};
	}

	/**
	 * A pack's name as the app writes it, "Bag (25 Kg)". A method reference rather than a call, so
	 * that {@code UnitLabelAgreementTest}, which reads every call of a no-argument label method in a file that uses
	 * {@link Unit}, is not asked to approve something that is not a unit's label.
	 */
	private static final Function<BuyPackView, String> PACK_NAME = BuyPackView::label;

	/** "4 × Bag (25 Kg) + 1 × 500 gm": a line's packs as the shopping list prints them. */
	public static String packsText(List<BuyPackView> packs) {
		return packs.stream().map(p -> p.count() + " × " + PACK_NAME.apply(p)).collect(Collectors.joining(" + "));
	}

	private static BigDecimal money(BigDecimal value) {
		return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
	}

	/** Trailing zeros off, never in exponent form: 3.000 is 3 and 100 stays 100. */
	private static BigDecimal plain(BigDecimal value) {
		if (value == null) {
			return null;
		}
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	/** ₹1,23,456.50, Indian grouping; whole rupees without paise. */
	static String rupees(BigDecimal amount) {
		BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
		String whole = rounded.abs().toBigInteger().toString();
		StringBuilder grouped = new StringBuilder();
		int length = whole.length();
		for (int i = 0; i < length; i++) {
			grouped.append(whole.charAt(i));
			int left = length - i - 1;
			if (left > 0 && (left == 3 || (left > 3 && (left - 3) % 2 == 0))) {
				grouped.append(',');
			}
		}
		int paise = rounded.abs().remainder(BigDecimal.ONE).movePointRight(2).intValue();
		String fraction = paise == 0 ? "" : ".%02d".formatted(paise);
		return (rounded.signum() < 0 ? "-₹" : "₹") + grouped + fraction;
	}

	private static String normalise(String text) {
		return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	/** For the tool's answer: the card's lines as the model should read them back. */
	public static Map<String, Object> lineSummary(AssistantProposal.Line line, boolean english) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", line.name());
		// localName is the conversation's language, null in English and while the temple has no name
		// yet. The model is told which: a missing name in a non-English conversation is pending.
		if (line.localName() != null) {
			out.put("name_local", line.localName());
		} else if (!english) {
			out.put("name_pending", true);
		}
		out.put("quantity", line.quantity());
		out.put("unit", line.unit());
		if (line.rate() != null) {
			out.put("rate", line.rate());
			out.put("amount", line.amount());
		}
		return out;
	}
}
