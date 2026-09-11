package org.iskcon.kms.purchaseorder;

import java.util.UUID;

/**
 * An order that has just been raised: what the system knows it by, and what a person calls it.
 *
 * <p>Both halves are returned because both are needed, and only one of them was (T-134). The id is
 * how the screen links to the order. The number — {@code PO-2026-0041} — is what the confirmation
 * says out loud, and it is the half Rajeev asked for when he described the shopping list's vendor
 * tiles (D-24 §6): on save "a green confirmation naming the PO number appears and fades". A uuid
 * names nothing anybody can repeat to a colleague or read off a printed sheet.
 *
 * <p>Returned rather than fetched back. The number is allocated inside the same transaction that
 * writes the order, so it is already in hand; a second request to read one string would be a round
 * trip spent on a fact the first response could have carried.
 */
public record CreatedPurchaseOrder(UUID id, String poNumber) {
}
