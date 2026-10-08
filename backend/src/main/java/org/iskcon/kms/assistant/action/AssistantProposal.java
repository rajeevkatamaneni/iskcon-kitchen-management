package org.iskcon.kms.assistant.action;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A draft the assistant made, as the chat page's card shows it (E10-S4). The shape is the browser's
 * {@code AssistantProposal} in {@code frontend/lib/api.ts}, field for field, plus {@code result}, which
 * the contract adds on both sides.
 *
 * <p>Sent three ways, always this record: as the {@code proposal} event at the end of the run that
 * made it, as the answer to Confirm and Reject, and under each assistant message when a conversation
 * is read back, so a reload shows the same cards in their current state.
 *
 * @param status {@code PROPOSED}, {@code CONFIRMED}, {@code REJECTED} or {@code EXPIRED}. EXPIRED is
 *     worked out when the draft is read (a PROPOSED row past {@code expiresAt}); nothing has to run at
 *     midnight to mark it.
 * @param result what Confirm made, once it has: {@code {label: "PO-2026-0047", href: "/orders/<id>"}}.
 *     Null until then.
 */
public record AssistantProposal(
		UUID actionId,
		String kind,
		String status,
		String title,
		List<Fact> facts,
		List<Line> lines,
		String confirmSays,
		Instant expiresAt,
		Result result) {

	/** One label-over-value cell in the card's readout row: Vendor, Needed by, Total. */
	public record Fact(String label, String value, String hint) {
	}

	/**
	 * One line on the card. {@code rate} and {@code amount} are rupees and only for kinds that carry a
	 * price; null where no price is known, which the card shows as a dash rather than ₹0.
	 */
	public record Line(String name, String localName, BigDecimal quantity, String unit, BigDecimal rate,
			BigDecimal amount) {
	}

	/** What a confirmed draft became, and where in the app to see it. */
	public record Result(String label, String href) {
	}

	/**
	 * Everything on the card that is decided when the draft is made. Stored inside the hashed payload
	 * beside the request, so the card the person reads is covered by the same hash as the thing that
	 * Confirm carries out: a card edited after the fact is refused exactly like a changed request.
	 */
	public record Card(String title, List<Fact> facts, List<Line> lines, String confirmSays) {
	}
}
