package org.iskcon.kms.assistant.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.iskcon.kms.assistant.action.AssistantProposal;
import org.iskcon.kms.assistant.names.ConversationNames;
import org.iskcon.kms.assistant.action.PendingActionService;
import org.iskcon.kms.assistant.action.PurchaseOrderAction;
import org.iskcon.kms.assistant.action.PurchaseOrderAction.RequestedLine;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.shoppinglist.ShoppingListLineView;
import org.iskcon.kms.shoppinglist.ShoppingListService;
import org.iskcon.kms.vendor.VendorView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The assistant's purchase-order tools (E10-S4; plan §11 S9): read the shopping list, find a vendor,
 * and draft an order for the person to confirm.
 *
 * <h2>Permissions</h2>
 *
 * <p>All three need {@code MANAGE_PURCHASE_ORDERS}, the permission the shopping list and the order
 * screen declare, held by the person the agent token speaks for. The draft also needs the token's
 * {@code agent:propose} scope: it is the one tool here that stores anything, and what it stores is a
 * draft that changes nothing until the person confirms it on their own sign-in
 * ({@link PendingActionService}).
 *
 * <h2>Names</h2>
 *
 * <p>Item names in the conversation's language ({@code name_local}, or {@code name_pending: true} while
 * the temple has none yet) come from the temple's glossary only, never from a translation at call
 * time, for the reason {@link InventoryTools} gives (E10-T8b: a model translating names spells them
 * differently each time). {@link ConversationNames} reads them, for the shopping list here and for the
 * draft card in {@link PurchaseOrderAction}, so the list and the card can never disagree.
 */
@Component
public class PurchaseOrderTools {

	/** Shopping-list lines returned in one call, across all vendors (plan §3: every result is capped). */
	static final int MAX_LINES = 60;

	private final ShoppingListService shoppingList;
	private final ConversationNames names;
	private final PurchaseOrderAction purchaseOrders;

	public PurchaseOrderTools(ShoppingListService shoppingList, ConversationNames names,
			PurchaseOrderAction purchaseOrders) {
		this.shoppingList = shoppingList;
		this.names = names;
		this.purchaseOrders = purchaseOrders;
	}

	/**
	 * The shopping list as it stands now, grouped by the vendor each line would be ordered from, in
	 * the order the screen shows the groups (first appearance; the group with no vendor last).
	 */
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public Map<String, Object> getShoppingList() {
		ConversationNames.Names local = names.forCurrentAgent();
		List<ShoppingListLineView> all = shoppingList.list();
		Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
		List<Map<String, Object>> noVendor = new ArrayList<>();
		int shown = 0;
		for (ShoppingListLineView line : all) {
			if (shown >= MAX_LINES) {
				break;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", line.ingredientName());
			local.putInto(row, line.ingredientName());
			// In the unit it reads in, as the screen shows it: 2792 gm is 2.792 Kg (R-SL-1).
			PurchaseOrderAction.Shown readable = PurchaseOrderAction.Shown.of(line.suggestedQty(), Unit.valueOf(line.unit()));
			row.put("quantity", readable.value());
			row.put("unit", readable.unit().label(readable.value()));
			if (line.buyPacks() != null && !line.buyPacks().isEmpty()) {
				row.put("buy", PurchaseOrderAction.packsText(line.buyPacks()));
			}
			if (line.neededBy() != null) {
				row.put("needed_by", line.neededBy());
			}
			if (line.orderBy() != null) {
				row.put("order_by", line.orderBy());
			}
			row.put("ticked", line.included());
			if (line.suggestedVendorId() == null) {
				noVendor.add(row);
			} else {
				groups.computeIfAbsent(line.suggestedVendorName(), k -> new ArrayList<>()).add(row);
			}
			shown++;
		}
		List<Map<String, Object>> vendors = new ArrayList<>();
		groups.forEach((vendor, lines) -> vendors.add(group(vendor, lines)));
		if (!noVendor.isEmpty()) {
			vendors.add(group(null, noVendor));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("vendors", vendors);
		result.put("returned", shown);
		result.put("total_lines", all.size());
		result.put("truncated", all.size() > shown);
		local.fillIfMissing();
		return result;
	}

	private static Map<String, Object> group(String vendor, List<Map<String, Object>> lines) {
		Map<String, Object> group = new LinkedHashMap<>();
		group.put("vendor", vendor);
		group.put("lines", lines);
		return group;
	}

	/** Active vendors matching a name, as {@link PurchaseOrderAction#findVendors} matches them. */
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
	public Map<String, Object> findVendor(String name) {
		List<VendorView> found = purchaseOrders.findVendors(name);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (VendorView vendor : found.subList(0, Math.min(found.size(), InventoryTools.MAX_ROWS))) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", vendor.name());
			if (vendor.contactPerson() != null) {
				row.put("contact", vendor.contactPerson());
			}
			rows.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("vendors", rows);
		result.put("total_matching", found.size());
		return result;
	}

	/** Drafts one order. Stores a draft for the person to confirm and changes nothing else. */
	@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS') and hasAuthority('agent:propose')")
	public Map<String, Object> proposePurchaseOrder(String vendor, Object lines, String neededBy) {
		AgentPrincipal agent = currentAgent();
		LocalDate date = null;
		if (neededBy != null) {
			try {
				date = LocalDate.parse(neededBy.trim());
			} catch (DateTimeParseException e) {
				throw new McpServerConfiguration.ToolRefusal(
						"needed_by must be a date written YYYY-MM-DD, for example 2026-10-14.");
			}
		}
		PurchaseOrderAction.Drafted drafted;
		try {
			drafted = purchaseOrders.draft(agent, vendor, requestedLines(lines), date);
		} catch (PurchaseOrderAction.CannotDraft e) {
			throw new McpServerConfiguration.ToolRefusal(e.getMessage());
		} catch (PendingActionService.NoRunningRun e) {
			throw new McpServerConfiguration.ToolRefusal(NO_RUN_MESSAGE);
		}
		AssistantProposal proposal = drafted.proposal();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("draft_id", proposal.actionId());
		result.put("status", "DRAFT_READY_TO_CONFIRM");
		result.put("vendor", drafted.vendor().name());
		result.put("needed_by", drafted.neededBy());
		result.put("lines", proposal.lines().stream()
				.map(line -> PurchaseOrderAction.lineSummary(line, drafted.english())).toList());
		result.put("total", drafted.total());
		if (drafted.note() != null) {
			result.put("note", drafted.note());
		}
		result.put("summary", "A draft purchase order for " + drafted.vendor().name() + " with "
				+ proposal.lines().size() + (proposal.lines().size() == 1 ? " line" : " lines")
				+ " is shown to the person as a card. Nothing is saved until they press Confirm on it, and"
				+ " confirming saves a draft order that is not sent to the vendor.");
		return result;
	}

	static final String NO_RUN_MESSAGE = "The draft could not be stored because this answer is no longer"
			+ " running. Tell the person to ask again.";

	/**
	 * The model's {@code lines} argument: a list of {@code {item, quantity, unit}}. Null, absent or empty
	 * means "from the shopping list". Anything else that is not that shape is refused in words.
	 */
	static List<RequestedLine> requestedLines(Object lines) {
		if (lines == null) {
			return List.of();
		}
		if (!(lines instanceof List<?> list)) {
			throw new McpServerConfiguration.ToolRefusal(
					"lines must be a list of {item, quantity, unit}, or left out to use the shopping list.");
		}
		List<RequestedLine> out = new ArrayList<>();
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> map)) {
				throw new McpServerConfiguration.ToolRefusal("Each line must be {item, quantity, unit}.");
			}
			Object item = map.get("item");
			Object quantity = map.get("quantity");
			Object unit = map.get("unit");
			BigDecimal amount;
			try {
				amount = quantity == null ? null : new BigDecimal(quantity.toString().trim());
			} catch (NumberFormatException e) {
				throw new McpServerConfiguration.ToolRefusal("quantity must be a number, for example 25.");
			}
			out.add(new RequestedLine(Objects.toString(item, null), amount, Objects.toString(unit, null)));
		}
		return out;
	}

	/** The agent the token named; anything else is refused, as in {@link ConversationTools}. */
	private static AgentPrincipal currentAgent() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AgentPrincipal agent) {
			return agent;
		}
		throw new McpServerConfiguration.ToolRefusal(NO_RUN_MESSAGE);
	}
}
