package org.iskcon.kms.purchaseorder;

/**
 * Marking an order as sent, with the one thing the server cannot know (T-137, D-25).
 *
 * <p><strong>{@code sendAnyway} is the override on the late warning, and nothing else.</strong> An
 * order going out after the vendor's agreed lead time is refused with {@code KMS-400148} until
 * somebody says they mean it — <em>"That is a FAVOR we are asking"</em> — and this is how they say
 * it. The second press is the consent; the first showed them what it costs, which is that a delay
 * on this delivery cannot then be counted against the supplier.
 *
 * <p><strong>A primitive boolean, so an absent field means false — deliberate, and the same reading
 * {@code CancelPoRequest.vendorAbandoned} takes for the same reason.</strong> The standing warning
 * about booleans that deserialise to false is about fields where false is a claim. This one is the
 * opposite: false is the absence of a claim, so an older client, a script, or a test written before
 * D-25 gets the reading that keeps the vendor's promise rather than one that quietly waives it.
 *
 * <p>The whole body is optional on the endpoint, which is what keeps every existing caller working.
 */
public record SendPurchaseOrderRequest(boolean sendAnyway) {
}
