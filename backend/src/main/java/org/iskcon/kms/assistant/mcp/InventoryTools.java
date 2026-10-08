package org.iskcon.kms.assistant.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.iskcon.kms.assistant.names.ConversationNames;
import org.iskcon.kms.assistant.names.ConversationNames.Names;
import org.iskcon.kms.ingredient.IngredientNames;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryItemService;
import org.iskcon.kms.inventory.StockCover;
import org.iskcon.kms.inventory.StockItemView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * What the Kitchen Assistant's two stock tools actually read (E10-T6): {@code get_inventory} and
 * {@code list_low_stock}.
 *
 * <h2>Which services, and why not the ones the plan names</h2>
 *
 * <p>The plan says {@code get_inventory} is "backed by {@code StockFactsService}" and
 * {@code list_low_stock} by {@code LowStockAlertService}. Read closely, neither can be called as it
 * stands, and both point at the same real source:
 *
 * <ul>
 *   <li>{@code StockFactsService} holds three of the facts — last counted, on order, the daily rate
 *       behind "how long it lasts" — but not on hand, available or the reorder level. The one place
 *       all of them are put together is {@link InventoryItemService#list}, which calls
 *       {@code StockFactsService} for exactly this, in aggregate, once per screen. So this reads that.
 *   <li>{@code LowStockAlertService} has one public method, {@code sendDailyDigest()}, and it
 *       <em>writes</em>: it claims today's digest row and sends WhatsApp and email to every cook in
 *       the temple. A read-only tool must not do that. What the digest itself reads is
 *       {@link InventoryItemService#lowStock()}, "the single source of what's low" shared by the
 *       dashboard, the digest and the low-stock endpoint — so this reads that too, and the assistant
 *       cannot disagree with the screen about what is low.
 * </ul>
 *
 * <p>Nothing here queries a table. The brief was to reuse, and these two methods are the reuse.
 *
 * <h2>The permission</h2>
 *
 * <p>{@code MANAGE_INVENTORY}, the same one the stock screen's own endpoints declare. The agent
 * token's authorities are its scopes <em>plus the signed-in person's own permissions</em> (the
 * contract between T5 and this task), so a person whose role cannot open the stock screen cannot
 * have it read out to them either. The {@code agent:read} scope is checked once, on the
 * {@code /mcp} endpoint itself.
 *
 * <h2>Names in the conversation's language</h2>
 *
 * <p>The S1 spike found that a model answering in Kannada does far better when the tool hands it
 * the Kannada name than when it is left to translate one, and E10-T8b measured why it matters: left
 * to translate, it spelt 12 of 28 items more than one way across five repeats of the same question,
 * and a cook matching a word to a sack needs the same word every time. So each item carries
 * {@code name_local}, the temple's stored name in the <em>conversation's</em> language (E10-NAMES-B;
 * until then it was {@code name_kn}, Kannada only, whatever language the conversation was in). The
 * name is read from the temple's glossary through {@link ConversationNames}, keyed on the name as the
 * temple filed it, because that is how the temple typed them in; machine-written rows included,
 * because to the reader they are the temple's name until somebody corrects them.
 *
 * <p>A tool call never translates or writes. Where the glossary has no name yet the item says
 * {@code name_pending: true}, the description tells the model what to do then, and the call asks the
 * fill to run without waiting for it. In an English conversation neither field is sent.
 */
@Component
public class InventoryTools {

	/** Every tool result is capped, so one call cannot fill the model's context (plan §3). */
	static final int MAX_ROWS = 50;

	private final InventoryItemService inventoryItemService;
	private final ConversationNames names;

	public InventoryTools(InventoryItemService inventoryItemService, ConversationNames names) {
		this.inventoryItemService = inventoryItemService;
		this.names = names;
	}

	/**
	 * Stock for every consumable whose name contains {@code itemName}, or every consumable when it is
	 * blank.
	 *
	 * <p>The match is a case-insensitive substring of the filed English name, the same name read
	 * aloud ({@code "Water, hot"} also answers to {@code "hot water"}), and the stored name in the
	 * conversation's language. Filtered
	 * in Java over the whole list rather than in SQL, because the list is the service's own read and
	 * it is already one pass for the whole temple — a second query shape for one item would be the
	 * "second way of reading the ledger" this package keeps refusing.
	 */
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public InventoryResult getInventory(String itemName) {
		Names local = names.forCurrentAgent();
		String needle = itemName == null ? "" : itemName.trim().toLowerCase(Locale.ROOT);

		List<InventoryRow> matching = inventoryItemService.list(null, null, null).stream()
				.map(view -> toRow(view, local))
				.filter(row -> needle.isEmpty() || matches(row, needle))
				.toList();
		InventoryResult result = InventoryResult.capped(matching);
		local.fillIfMissing();
		return result;
	}

	/** The consumables below their reorder level, exactly as the dashboard and the digest see them. */
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public InventoryResult listLowStock() {
		Names local = names.forCurrentAgent();
		List<InventoryRow> low = inventoryItemService.lowStock().stream()
				.map(view -> toRow(view, local))
				.toList();
		InventoryResult result = InventoryResult.capped(low);
		local.fillIfMissing();
		return result;
	}

	private static boolean matches(InventoryRow row, String needle) {
		return row.name().toLowerCase(Locale.ROOT).contains(needle)
				|| IngredientNames.readable(row.name()).toLowerCase(Locale.ROOT).contains(needle)
				|| (row.nameLocal() != null && row.nameLocal().toLowerCase(Locale.ROOT).contains(needle));
	}

	private static InventoryRow toRow(StockItemView view, Names local) {
		Unit unit = Unit.valueOf(view.unit());
		String nameLocal = local.local(view.ingredientName());
		return new InventoryRow(
				view.ingredientName(),
				nameLocal,
				local.english() || nameLocal != null ? null : Boolean.TRUE,
				view.category(),
				quantity(view.onHand()),
				quantity(view.available()),
				unit.label(),
				quantity(view.reorderThreshold()),
				view.belowThreshold(),
				quantity(view.onOrder()),
				Cover.of(view.lastsFor()));
	}

	/**
	 * Three decimal places at most, trailing zeros dropped: {@code 173.542000} reads as
	 * {@code 173.542} and {@code 5.000} as {@code 5}. A gram of a kilo is the finest anybody weighs
	 * at, and six digits of noise is context the model pays for and then repeats.
	 */
	private static BigDecimal quantity(BigDecimal value) {
		if (value == null) {
			return null;
		}
		BigDecimal rounded = value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
		// stripTrailingZeros turns 100 into 1E+2; the scale is put back so it is written as 100.
		return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
	}

	/**
	 * One tool result. {@code totalMatching} and {@code truncated} are there so the model can say
	 * "here are the first 50 of 114" rather than presenting a cut list as the whole temple.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record InventoryResult(List<InventoryRow> items, int returned, int totalMatching, boolean truncated) {

		static InventoryResult capped(List<InventoryRow> all) {
			List<InventoryRow> shown = all.size() > MAX_ROWS ? all.subList(0, MAX_ROWS) : all;
			return new InventoryResult(List.copyOf(shown), shown.size(), all.size(), all.size() > MAX_ROWS);
		}
	}

	/**
	 * One consumable as the model sees it. Field meanings are documented once, in the tool
	 * descriptions in {@link McpServerConfiguration}, because that is the only documentation the
	 * model ever reads.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record InventoryRow(
			String name,
			String nameLocal,
			Boolean namePending,
			String category,
			BigDecimal onHand,
			BigDecimal available,
			String unit,
			BigDecimal reorderLevel,
			boolean low,
			BigDecimal onOrder,
			Cover cover) {
	}

	/**
	 * How long what is on the shelf lasts, or {@code known: false}.
	 *
	 * <p>Always present on a row, never null, so "not enough history to judge" is something the model
	 * is told rather than something it infers from a missing field — {@code StockCover}'s own rule is
	 * that the screen must say so in words, and this is the model's version of the words.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Cover(boolean known, Integer days, Boolean moreThan, LocalDate runsOutOn, BigDecimal usePerDay) {

		static Cover of(StockCover cover) {
			if (cover == null) {
				return new Cover(false, null, null, null, null);
			}
			return new Cover(true, cover.days(), cover.beyondWindow(), cover.runsOutOn(), quantity(cover.perDay()));
		}
	}
}
